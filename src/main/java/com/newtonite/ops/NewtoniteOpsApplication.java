package com.newtonite.ops;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class NewtoniteOpsApplication {

	public static void main(String[] args) {
		SpringApplication.run(NewtoniteOpsApplication.class, args);
	}
}
