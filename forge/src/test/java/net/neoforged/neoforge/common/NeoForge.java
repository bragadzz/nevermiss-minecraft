package net.neoforged.neoforge.common;

import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class NeoForge {
    public static final IEventBus EVENT_BUS = new Barramento();

    public static final class Barramento implements IEventBus {
        private final List<Object[]> ouvintes = new ArrayList<>();

        @Override
        public <T> void addListener(EventPriority prioridade, boolean cancelados, Class<T> evento, Consumer<T> tratador) {
            ouvintes.add(new Object[]{evento, tratador});
        }

        @SuppressWarnings("unchecked")
        public void disparar(Object evento) {
            for (Object[] o : ouvintes) if (((Class<?>) o[0]).isInstance(evento)) ((Consumer<Object>) o[1]).accept(evento);
        }
    }
}
