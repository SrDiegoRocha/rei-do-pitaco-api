package com.example.reidopitaco.service;

import com.example.reidopitaco.dto.request.PushSubscriptionRequest;
import com.example.reidopitaco.entity.PushSubscription;
import com.example.reidopitaco.entity.User;
import com.example.reidopitaco.exception.InvalidTokenException;
import com.example.reidopitaco.repository.PushSubscriptionRepository;
import com.example.reidopitaco.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Registra e remove inscrições Web Push do usuário autenticado. A inscrição é
 * identificada pelo endpoint (único por dispositivo); se já existir, é
 * reaproveitada e reassociada ao usuário atual.
 */
@Service
public class PushSubscriptionService {

    private final PushSubscriptionRepository subscriptionRepository;
    private final UserRepository userRepository;

    public PushSubscriptionService(
            PushSubscriptionRepository subscriptionRepository,
            UserRepository userRepository
    ) {
        this.subscriptionRepository = subscriptionRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public void subscribe(UUID userPublicId, PushSubscriptionRequest request) {
        User user = userRepository.findByPublicId(userPublicId)
                .filter(User::isActive)
                .orElseThrow(InvalidTokenException::new);

        PushSubscription subscription = subscriptionRepository.findByEndpoint(request.endpoint())
                .orElseGet(PushSubscription::new);
        subscription.setUser(user);
        subscription.setEndpoint(request.endpoint());
        subscription.setP256dh(request.p256dh());
        subscription.setAuth(request.auth());
        subscriptionRepository.save(subscription);
    }

    @Transactional
    public void unsubscribe(UUID userPublicId, String endpoint) {
        subscriptionRepository.findByEndpoint(endpoint)
                .filter(sub -> sub.getUser().getPublicId().equals(userPublicId))
                .ifPresent(subscriptionRepository::delete);
    }
}
