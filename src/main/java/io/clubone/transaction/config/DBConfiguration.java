package io.clubone.transaction.config;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class DBConfiguration {

	@Primary
	@Bean(name = "cluboneDb")
	@ConfigurationProperties(prefix = "spring.datasource.clubone")
	public DataSource cluboneDataSource() {
		return DataSourceBuilder.create().build();
	}

	@Bean(name = "cluboneJdbcTemplate")
	public JdbcTemplate cluboneJdbcTemplate(@Qualifier("cluboneDb") DataSource cluboneDb) {
		return new JdbcTemplate(cluboneDb, false);
	}
}
