package net.neoforged.bus.api;

import java.util.function.Consumer;

public interface IEventBus {
    <T> void addListener(EventPriority prioridade, boolean cancelados, Class<T> evento, Consumer<T> tratador);
}
