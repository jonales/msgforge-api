package dev.msgforge.web;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** {@link ManagerBus} de um nó só: entrega na hora, na thread de quem publicou. */
@Component
class LocalManagerBus implements ManagerBus {

    private static final Logger log = LoggerFactory.getLogger(LocalManagerBus.class);

    private final List<Consumer<ManagerUpdate>> subscribers = new CopyOnWriteArrayList<>();

    @Override
    public void publish(ManagerUpdate update) {
        for (var subscriber : subscribers) {
            try {
                subscriber.accept(update);
            } catch (RuntimeException e) {
                log.warn("Manager: assinante do barramento falhou em {}: {}", update, e.getMessage());
            }
        }
    }

    @Override
    public void subscribe(Consumer<ManagerUpdate> subscriber) {
        subscribers.add(subscriber);
    }
}
