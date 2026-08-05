package in.nirman.modules.expense.service;

import in.nirman.common.BusinessException;
import in.nirman.modules.expense.api.dto.ExpenseDtos.DuplicateCandidate;
import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * Thrown when a new expense looks like one already booked.
 *
 * <p>Carries the candidates rather than just refusing, because the person entering it is the
 * only one who can tell a genuine second delivery from a double entry — and they can only do
 * that if they are shown what they are being compared against. A bare "duplicate expense"
 * sends somebody hunting through a month of paper for a row the server already had in
 * hand.</p>
 *
 * <p>Its own exception type rather than a {@link BusinessException} because the response
 * body is a different shape: RFC 7807 plus a list. {@code GlobalExceptionHandler} renders
 * it.</p>
 */
public class DuplicateExpenseException extends RuntimeException {

    private final transient List<DuplicateCandidate> candidates;

    public DuplicateExpenseException(String message, List<DuplicateCandidate> candidates) {
        super(message);
        this.candidates = candidates;
    }

    public List<DuplicateCandidate> getCandidates() {
        return candidates;
    }

    public HttpStatus getStatus() {
        return HttpStatus.CONFLICT;
    }
}
