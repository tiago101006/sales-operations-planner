package pt.tiagodias.sop;

import org.springframework.boot.SpringApplication;

public class TestSalesOperationsPlannerApplication {

	public static void main(String[] args) {
		SpringApplication.from(SalesOperationsPlannerApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
