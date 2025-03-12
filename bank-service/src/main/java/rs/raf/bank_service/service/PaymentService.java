package rs.raf.bank_service.service;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import rs.raf.bank_service.client.UserClient;
import rs.raf.bank_service.domain.dto.*;
import rs.raf.bank_service.domain.entity.Account;
import rs.raf.bank_service.domain.entity.CompanyAccount;
import rs.raf.bank_service.domain.entity.Payment;
import rs.raf.bank_service.domain.enums.CurrencyType;
import rs.raf.bank_service.domain.enums.PaymentStatus;
import rs.raf.bank_service.domain.enums.VerificationType;
import rs.raf.bank_service.exceptions.*;
import rs.raf.bank_service.mapper.PaymentMapper;
import rs.raf.bank_service.repository.AccountRepository;
import rs.raf.bank_service.repository.PaymentRepository;
import rs.raf.bank_service.repository.CardRepository;
import rs.raf.bank_service.specification.PaymentSpecification;
import rs.raf.bank_service.utils.JwtTokenUtil;

import javax.validation.constraints.NotNull;
import javax.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

@Service
@AllArgsConstructor
public class PaymentService {

    private final AccountRepository accountRepository;
    private final JwtTokenUtil jwtTokenUtil;
    private PaymentRepository paymentRepository;
    private CardRepository cardRepository;
    private final UserClient userClient;
    private final PaymentMapper paymentMapper;
    private final ObjectMapper objectMapper;
    private final ExchangeRateService exchangeRateService;

    public boolean createTransferPendingConfirmation(TransferDto transferDto, Long clientId) throws JsonProcessingException {
        // Preuzimanje računa za sender i receiver
        Account sender = accountRepository.findByAccountNumber(transferDto.getSenderAccountNumber())
                .stream().findFirst()
                .orElseThrow(() -> new SenderAccountNotFoundException(transferDto.getSenderAccountNumber()));

        Account receiver = accountRepository.findByAccountNumber(transferDto.getReceiverAccountNumber())
                .stream().findFirst()
                .orElseThrow(() -> new ReceiverAccountNotFoundException(transferDto.getReceiverAccountNumber()));

        // Provera da li sender ima dovoljno sredstava
        if (sender.getBalance().compareTo(transferDto.getAmount()) < 0) {
            throw new InsufficientFundsException(sender.getBalance(), transferDto.getAmount());
        }

        BigDecimal amount = transferDto.getAmount();
        BigDecimal convertedAmount = amount;
        BigDecimal exchangeRateValue = BigDecimal.ONE;



        // Provera da li su valute različite
        if (!sender.getCurrency().equals(receiver.getCurrency())) {
            // Dobijanje kursa konverzije
            ExchangeRateDto exchangeRateDto = exchangeRateService.getExchangeRate(sender.getCurrency().getCode(), receiver.getCurrency().getCode());
            exchangeRateValue = exchangeRateDto.getExchangeRate();

            // Konverzija iznosa u valutu receiver-a
            convertedAmount = amount.multiply(exchangeRateValue);
        }

        // Kreiranje Payment entiteta za transfer
        Payment payment = new Payment();
        payment.setClientId(clientId);  // Dodajemo Client ID
        payment.setSenderAccount(sender);  // Sender račun
        payment.setAmount(transferDto.getAmount());  // Iznos
        payment.setAccountNumberReceiver(transferDto.getReceiverAccountNumber());  // Primalac (receiver)
        payment.setStatus(PaymentStatus.PENDING_CONFIRMATION);  // Status je "na čekanju"
        payment.setDate(LocalDateTime.now());  // Datum transakcije
        payment.setOutAmount(convertedAmount);

        // Postavi receiverClientId samo ako je receiver u našoj banci
        payment.setReceiverClientId(receiver.getClientId());  // Postavljamo receiverClientId

        paymentRepository.save(payment);

        PaymentVerificationDetailsDto paymentVerificationDetailsDto = PaymentVerificationDetailsDto.builder()
                .fromAccountNumber(sender.getAccountNumber())
                .toAccountNumber(transferDto.getReceiverAccountNumber())
                .amount(transferDto.getAmount())
                .build();

        // Kreiraj PaymentVerificationRequestDto i pozovi UserClient da kreira verificationRequest
        CreateVerificationRequestDto paymentVerificationRequestDto = new CreateVerificationRequestDto(clientId, payment.getId(), VerificationType.TRANSFER, objectMapper.writeValueAsString(paymentVerificationDetailsDto));
        userClient.createVerificationRequest(paymentVerificationRequestDto);

        return true;
    }

    public boolean confirmTransferAndExecute(Long paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));

        Account sender = payment.getSenderAccount();
        Account receiver = accountRepository.findByAccountNumber(payment.getAccountNumberReceiver())
                .orElseThrow(() -> new ReceiverAccountNotFoundException(payment.getAccountNumberReceiver()));

        BigDecimal amount = payment.getAmount();
        BigDecimal convertedAmount = amount;
        BigDecimal exchangeRateValue = BigDecimal.ONE;

        //  Obezbeđujemo da transakcije idu preko bankovnih računa (companyId = 1)
        CompanyAccount bankAccountFrom = accountRepository.findFirstByCurrencyAndCompanyId(sender.getCurrency(), 1L)
                .orElseThrow(() -> new BankAccountNotFoundException("No bank account found for currency: " + sender.getCurrency().getCode()));

        CompanyAccount bankAccountTo = accountRepository.findFirstByCurrencyAndCompanyId(receiver.getCurrency(), 1L)
                .orElseThrow(() -> new BankAccountNotFoundException("No bank account found for currency: " + receiver.getCurrency().getCode()));

        //  Ako su valute različite, koristimo kursnu listu
        if (!sender.getCurrency().getCode().equals(receiver.getCurrency().getCode())) {
            ExchangeRateDto exchangeRateDto = exchangeRateService.getExchangeRate(sender.getCurrency().getCode(), receiver.getCurrency().getCode());
            exchangeRateValue = exchangeRateDto.getExchangeRate();
            convertedAmount = amount.multiply(exchangeRateValue);

            //  Sender -> Banka (ista valuta)
            sender.setBalance(sender.getBalance().subtract(amount));
            bankAccountFrom.setBalance(bankAccountFrom.getBalance().add(amount));
            accountRepository.save(sender);
            accountRepository.save(bankAccountFrom);

            //  Banka konvertuje u drugu valutu
            bankAccountFrom.setBalance(bankAccountFrom.getBalance().subtract(convertedAmount));
            bankAccountTo.setBalance(bankAccountTo.getBalance().add(convertedAmount));
            accountRepository.save(bankAccountFrom);
            accountRepository.save(bankAccountTo);

            //  Banka -> Receiver
            bankAccountTo.setBalance(bankAccountTo.getBalance().subtract(convertedAmount));
            receiver.setBalance(receiver.getBalance().add(convertedAmount));
            accountRepository.save(bankAccountTo);
        } else {
            sender.setBalance(sender.getBalance().subtract(amount));
            receiver.setBalance(receiver.getBalance().add(amount));
        }

        accountRepository.save(sender);
        accountRepository.save(receiver);

        //  Čuvamo outAmount u Payment (stvarno primljen iznos)
        payment.setOutAmount(convertedAmount);
        payment.setStatus(PaymentStatus.COMPLETED);
        paymentRepository.save(payment);

        return true;
    }


    public boolean createPaymentBeforeConfirmation(CreatePaymentDto paymentDto, Long clientId) throws JsonProcessingException {
        if (paymentDto.getPaymentCode() == null || paymentDto.getPaymentCode().isEmpty()) {
            throw new PaymentCodeNotProvidedException();
        }

        if (paymentDto.getPurposeOfPayment() == null || paymentDto.getPurposeOfPayment().isEmpty()) {
            throw new PurposeOfPaymentNotProvidedException();
        }

        // Preuzimanje sender računa
        Account sender = accountRepository.findByAccountNumber(paymentDto.getSenderAccountNumber())
                .stream().findFirst()
                .orElseThrow(() -> new SenderAccountNotFoundException(paymentDto.getSenderAccountNumber()));

        Account receiver = accountRepository.findByAccountNumber(paymentDto.getReceiverAccountNumber())
                .stream().findFirst()
                .orElseThrow(() -> new ReceiverAccountNotFoundException(paymentDto.getReceiverAccountNumber()));


        // Provera valute
        if (!(sender.getCurrency().getCode().equals(CurrencyType.RSD.toString()))) {
            throw new SendersAccountsCurencyIsNotDinarException();
        }

        // Provera balansa sender računa
        if (sender.getBalance().compareTo(paymentDto.getAmount()) < 0) {
            throw new InsufficientFundsException(sender.getBalance(), paymentDto.getAmount());
        }

        ClientDto clientDto = userClient.getClientById(clientId);

        // Kreiranje Payment entiteta
        Payment payment = new Payment();
        payment.setSenderName(clientDto.getFirstName() + " " + clientDto.getLastName());
        payment.setClientId(clientId);
        payment.setSenderAccount(sender);
        payment.setAccountNumberReceiver(paymentDto.getReceiverAccountNumber());
        payment.setAmount(paymentDto.getAmount());
        payment.setPaymentCode(paymentDto.getPaymentCode());
        payment.setPurposeOfPayment(paymentDto.getPurposeOfPayment());
        payment.setReferenceNumber(paymentDto.getReferenceNumber());
        payment.setDate(LocalDateTime.now());
        payment.setStatus(PaymentStatus.PENDING_CONFIRMATION);

        // Postavi receiverClientId samo ako je receiver u našoj banci (za sad uvek postoji)
        payment.setReceiverClientId(receiver.getClientId());

        paymentRepository.save(payment);

        PaymentVerificationDetailsDto paymentVerificationDetailsDto = PaymentVerificationDetailsDto.builder()
                .fromAccountNumber(sender.getAccountNumber())
                .toAccountNumber(paymentDto.getReceiverAccountNumber())
                .amount(paymentDto.getAmount())
                .build();

        CreateVerificationRequestDto createVerificationRequestDto = new CreateVerificationRequestDto(clientId, payment.getId(), VerificationType.PAYMENT, objectMapper.writeValueAsString(paymentVerificationDetailsDto));
        userClient.createVerificationRequest(createVerificationRequestDto);

        return true;
    }

    public void confirmPayment(Long paymentId) {
        // Preuzimanje payment entiteta na osnovu paymentId
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));

        // Preuzimanje sender i receiver računa
        Account sender = payment.getSenderAccount();
        String receiverString = payment.getAccountNumberReceiver();

        Optional<Account> receiverOpt = accountRepository.findByAccountNumber(receiverString);

        // Ako je receiver u banci, izvrši transakciju
        if (receiverOpt.isPresent()) {
            Account receiver = receiverOpt.get();
            // Konverzija iznos sa RSD u valutu primaoca
            BigDecimal convertedAmount = convert(payment.getAmount(), CurrencyType.valueOf(receiver.getCurrency().getCode()));

            // Dodavanje iznosa na receiver račun u odgovarajućoj valuti
            receiver.setBalance(receiver.getBalance().add(convertedAmount));
            accountRepository.save(receiver);
        }

        sender.setBalance(sender.getBalance().subtract(payment.getAmount()));
        accountRepository.save(sender);

        // Ažuriranje statusa payment-a na "COMPLETED"
        payment.setStatus(PaymentStatus.COMPLETED);
        paymentRepository.save(payment);
    }

    public static BigDecimal convert(@NotNull(message = "Amount is required.") @Positive(message = "Amount must be positive.") BigDecimal amountInRSD, CurrencyType currencyType) {
        BigDecimal convertedAmount = BigDecimal.ZERO;  // Postavi početnu vrednost kao 0

        if (currencyType == CurrencyType.RSD) {
            convertedAmount = amountInRSD;
        } else if (currencyType == CurrencyType.EUR) {
            convertedAmount = amountInRSD.multiply(new BigDecimal("0.0085"));
        } else if (currencyType == CurrencyType.USD) {
            convertedAmount = amountInRSD.multiply(new BigDecimal("0.010"));
        } else if (currencyType == CurrencyType.HRK) {
            convertedAmount = amountInRSD.multiply(new BigDecimal("0.064"));
        } else if (currencyType == CurrencyType.JPY) {
            convertedAmount = amountInRSD.multiply(new BigDecimal("1.14"));
        } else if (currencyType == CurrencyType.GBP) {
            convertedAmount = amountInRSD.multiply(new BigDecimal("0.0076"));
        } else if (currencyType == CurrencyType.AUD) {
            convertedAmount = amountInRSD.multiply(new BigDecimal("0.014"));
        } else if (currencyType == CurrencyType.CHF) {
            convertedAmount = amountInRSD.multiply(new BigDecimal("0.0095"));
        } else {
            throw new CurrencyNotFoundException(currencyType.toString());
        }
        return convertedAmount;
    }

    // Dohvatanje svih transakcija za određenog klijenta sa filtriranjem
    public Page<PaymentOverviewDto> getPayments(
            String token,
            LocalDateTime startDate, LocalDateTime endDate,
            BigDecimal minAmount, BigDecimal maxAmount,
            PaymentStatus paymentStatus,
            String accountNumber,
            String cardNumber,
            Pageable pageable
    ) {
        Long clientId = jwtTokenUtil.getUserIdFromAuthHeader(token);

        if (accountNumber != null) {
            accountRepository.findByAccountNumber(accountNumber)
                    .orElseThrow(AccountNotFoundException::new);
        }

        if (cardNumber != null) {
            cardRepository.findByCardNumber(cardNumber)
                    .orElseThrow(() -> new CardNotFoundException(cardNumber));
        }

        Specification<Payment> spec = PaymentSpecification.filterPayments(clientId, startDate, endDate, minAmount, maxAmount, paymentStatus, accountNumber, cardNumber);
        Page<Payment> payments = paymentRepository.findAll(spec, pageable);
        return payments.map(paymentMapper::toOverviewDto);
    }

    // Dohvatanje detalja transakcije po ID-u
    public PaymentDetailsDto getPaymentDetails(String token, Long id) {
        Long clientId = jwtTokenUtil.getUserIdFromAuthHeader(token);
        Payment payment = paymentRepository.findByIdAndClientId(id, clientId)
                .orElseThrow(() -> new PaymentNotFoundException(id));
        return paymentMapper.toDetailsDto(payment);
    }
}
