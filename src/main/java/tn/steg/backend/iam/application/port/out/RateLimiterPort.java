package tn.steg.backend.iam.application.port.out;

public interface RateLimiterPort {

    boolean isRateLimited(String key);

    void reset(String key);
}
