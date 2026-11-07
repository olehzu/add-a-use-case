package team.projectpulse.student;

/**
 * Thrown when a deactivation is submitted for a student who is already deactivated, for example because another
 * instructor deactivated her first (UC-STU-deactivate-student, 6a). Mapped to {@code 409 CONFLICT}.
 */
public class StudentAlreadyDeactivatedException extends RuntimeException {

    public StudentAlreadyDeactivatedException() {
        super("This student is already deactivated.");
    }

}
