package io.github.viniciusclemos.booking.api;

import io.github.viniciusclemos.booking.scheduling.BookingRefused;
import io.github.viniciusclemos.booking.scheduling.NotFound;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;

@RestControllerAdvice
public class ApiErrors {

    public record Problem(String code, String message) {
    }

    public record ErrorBody(String error, List<Problem> problems) {
    }

    @ExceptionHandler(BookingRefused.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ErrorBody refused(BookingRefused e) {
        return new ErrorBody("This time can't be booked",
                e.violations().stream().map(v -> new Problem(v.name(), v.message())).toList());
    }

    @ExceptionHandler(NotFound.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    ErrorBody notFound(NotFound e) {
        return new ErrorBody(e.getMessage(), List.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ErrorBody invalid(MethodArgumentNotValidException e) {
        return new ErrorBody("Check the fields", e.getBindingResult().getFieldErrors().stream()
                .map(f -> new Problem(f.getField(), f.getDefaultMessage())).toList());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class, IllegalArgumentException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ErrorBody badRequest(Exception e) {
        String message = e instanceof IllegalArgumentException ? e.getMessage() : "Invalid request";
        return new ErrorBody(message, List.of());
    }
}
