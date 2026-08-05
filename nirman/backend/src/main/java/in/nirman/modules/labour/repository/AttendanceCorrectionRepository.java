package in.nirman.modules.labour.repository;

import in.nirman.modules.labour.domain.AttendanceCorrection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AttendanceCorrectionRepository extends JpaRepository<AttendanceCorrection, UUID> {

    /** The trail for one row, oldest first — how the day came to say what it now says. */
    List<AttendanceCorrection> findByAttendanceIdOrderByRequestedAtAsc(UUID attendanceId);
}
