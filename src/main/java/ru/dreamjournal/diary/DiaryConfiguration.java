package ru.dreamjournal.diary;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

@Configuration(proxyBeanMethods = false)
public class DiaryConfiguration {

    @Bean
    Clock diaryClock() {
        return Clock.systemUTC();
    }

    @ConfigurationProperties("diary")
    public record Properties(ZoneId defaultTimeZone) {
    }
}
