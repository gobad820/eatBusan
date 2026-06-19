package com.ssafy.eatBusan.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// @Scheduled 활성화. 메인 클래스 직접 수정 대신 별도 설정으로 분리한다.
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
