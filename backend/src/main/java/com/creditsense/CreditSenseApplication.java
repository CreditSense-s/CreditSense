package com.creditsense;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication; import org.springframework.data.jpa.repository.config.EnableJpaRepositories; import com.creditsense.repo.Repositories;
@SpringBootApplication @EnableJpaRepositories(basePackageClasses=Repositories.class,considerNestedRepositories=true) public class CreditSenseApplication { public static void main(String[] args) { SpringApplication.run(CreditSenseApplication.class, args); } }
