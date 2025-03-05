package rs.raf.bank_service.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
import rs.raf.bank_service.domain.dto.*;

import java.util.List;


/// Klasa koja sluzi za slanje HTTP poziva na userService
@FeignClient(name = "user-service", url = "${user.service.url:http://localhost:8080}",fallbackFactory = UserClientFallbackFactory.class,decode404 = true)
public interface UserClient {

    @GetMapping("/api/admin/clients/{id}")
    ClientDto getClientById(@PathVariable("id") Long id);

    @PostMapping("/api/auth/request-card")
    void requestCard(RequestCardDto requestCardDto);

    @PostMapping("/api/auth/check-token")
    void checkToken(CheckTokenDto checkTokenDto);

    @GetMapping("/api/company/{id}")
    CompanyDto getCompanyById(@PathVariable("id") Long id);

    @GetMapping("/api/authorized-personnel/company/{companyId}")
    List<AuthorizedPersonelDto> getAuthorizedPersonnelByCompany(@PathVariable("companyId") Long companyId);

    @PostMapping("/api/verification/request")
    void createVerificationRequest(@RequestBody VerificationRequestDto request);


    @GetMapping("/api/verification/status/{targetId}")
    boolean isVerificationApproved(@PathVariable("targetId") Long targetId, @RequestParam("code") String verificationCode);


}
