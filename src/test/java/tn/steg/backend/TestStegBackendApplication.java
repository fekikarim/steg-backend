package tn.steg.backend;

import org.springframework.boot.SpringApplication;

public class TestStegBackendApplication {

    public static void main(String[] args) {
        SpringApplication.from(StegBackendApplication::main).with(TestcontainersConfiguration.class).run(args);
    }

}
