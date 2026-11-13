package pl.rynek.chat.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * Kanał na żywo {@code /ws/chat} (STOMP przez zwykły WebSocket): nowa wiadomość, przeczytanie i „pisze…” trafiają na
 * {@code /user/queue/chat} rozmówcy, a „pisze…” klient zgłasza na {@code /app/chat.typing}. Przeglądarka nie może dodać nagłówka
 * {@code Authorization} do uzgadniania WebSocketu, więc token jest w ramce {@code CONNECT} (sprawdza go {@link StompAuthInterceptor}).
 * Broker jest w pamięci procesu: zdarzenia dochodzą tylko do klientów połączonych z tą samą instancją serwisu.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final StompAuthInterceptor auth;
    private final String[] allowedOrigins;

    public WebSocketConfig(StompAuthInterceptor auth, @Value("${rynek.chat.allowed-origins}") String[] allowedOrigins) {
        this.auth = auth;
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws/chat").setAllowedOriginPatterns(allowedOrigins);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // Bicie serca co 10 s utrzymuje połączenie przez proxy (gateway, dev server) i wykrywa zerwane łącza.
        registry.enableSimpleBroker("/queue").setHeartbeatValue(new long[] {10_000, 10_000}).setTaskScheduler(heartbeatScheduler());
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(auth);
    }

    @Bean
    ThreadPoolTaskScheduler heartbeatScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("chat-ws-heartbeat-");
        return scheduler;
    }
}
