package com.ssafy.eatBusan.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;

@Configuration
public class RedisConfig {
    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory redisConnectionFactory) {
        return new StringRedisTemplate(redisConnectionFactory);
    }

    @Bean
    public DefaultRedisScript<List> postLikeToggleScript(){
        DefaultRedisScript<List> redisScript = new DefaultRedisScript<>();
        redisScript.setLocation(new ClassPathResource("scripts/post-like-toggle.lua"));
        redisScript.setResultType(List.class);
        return redisScript;
    }

    @Bean
    public DefaultRedisScript<List> voteCastScript(){
        DefaultRedisScript<List> redisScript = new DefaultRedisScript<>();
        redisScript.setLocation(new ClassPathResource("scripts/vote-cast.lua"));
        redisScript.setResultType(List.class);
        return redisScript;
    }

    @Bean
    public DefaultRedisScript<Long> voteCompensateScript(){
        DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>();
        redisScript.setLocation(new ClassPathResource("scripts/vote-compensate.lua"));
        redisScript.setResultType(Long.class);
        return redisScript;
    }

    @Bean
    public DefaultRedisScript<List> voteTallyScript(){
        DefaultRedisScript<List> redisScript = new DefaultRedisScript<>();
        redisScript.setLocation(new ClassPathResource("scripts/vote-tally.lua"));
        redisScript.setResultType(List.class);
        return redisScript;
    }

}
