package dev.cadence;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CadenceApplication {
  public static void main(String[] args) {
    SpringApplication.run(CadenceApplication.class, args);
  }
}
