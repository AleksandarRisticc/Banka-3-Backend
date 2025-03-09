package rs.raf.user_service.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import rs.raf.user_service.domain.entity.ActivityCode;

public interface ActivityCodeRepository extends JpaRepository<ActivityCode, String> {

}
