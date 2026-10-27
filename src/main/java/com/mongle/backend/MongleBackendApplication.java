package com.mongle.backend;

import com.mongle.backend.migration.MigrationCommand;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class MongleBackendApplication {

	public static void main(String[] args) {
		if (MigrationCommand.requested(args)) {
			System.exit(MigrationCommand.run(args, System.getenv(), System.out));
			return;
		}
		SpringApplication.run(MongleBackendApplication.class, args);
	}

}
