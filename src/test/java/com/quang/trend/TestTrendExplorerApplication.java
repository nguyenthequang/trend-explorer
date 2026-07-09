package com.quang.trend;

import org.springframework.boot.SpringApplication;

public class TestTrendExplorerApplication {

	public static void main(String[] args) {
		SpringApplication.from(TrendExplorerApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
