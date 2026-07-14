package io.clubone.transaction.config;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import com.zaxxer.hikari.HikariDataSource;

@Configuration
public class DBConfiguration {

	@Primary
	@Bean(name = "cluboneDb")
	@ConfigurationProperties(prefix = "spring.datasource.clubone")
	public DataSource cluboneDataSource() {
		// Explicit Hikari so spring.datasource.clubone.hikari.* binds correctly
		return DataSourceBuilder.create().type(HikariDataSource.class).build();
	}

	@Bean(name = "cluboneJdbcTemplate")
	public JdbcTemplate cluboneJdbcTemplate(@Qualifier("cluboneDb") DataSource cluboneDb) {
		return new JdbcTemplate(cluboneDb, false);
	}
	
	 @Bean(name = "cluboneNamedJdbcTemplate")
	    public NamedParameterJdbcTemplate cluboneNamedJdbcTemplate(@Qualifier("cluboneDb") DataSource cluboneDb) {
	        return new NamedParameterJdbcTemplate(cluboneDb);
	    }
}
