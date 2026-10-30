package com.mongle.backend;

import com.mongle.backend.migration.MigrationCommand;
import com.mongle.backend.operations.credit.TestCreditCommand;
import com.mongle.backend.operations.payment.PaymentInspectCommand;
import com.mongle.backend.operations.payment.PaymentReclaimCommand;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class MongleBackendApplication {

	public static void main(String[] args) {
		if (PaymentReclaimCommand.requested(args)) {
			System.exit(PaymentReclaimCommand.run(args, System.getenv(), System.out));
			return;
		}
		if (PaymentInspectCommand.requested(args)) {
			System.exit(PaymentInspectCommand.run(args, System.getenv(), System.out));
			return;
		}
		if (TestCreditCommand.requested(args)) {
			System.exit(TestCreditCommand.run(args, System.getenv(), System.out));
			return;
		}
		if (MigrationCommand.requested(args)) {
			System.exit(MigrationCommand.run(args, System.getenv(), System.out));
			return;
		}
		SpringApplication.run(MongleBackendApplication.class, args);
	}

}
