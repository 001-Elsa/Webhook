package com.example.webhook.platform.service;

import com.example.webhook.platform.domain.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class StateMachineTest {
    @Test
    void succeededDeliveryCannotBeRetriedOrMovedBackward() {
        DeliveryTask task = new DeliveryTask();
        task.setStatus(DeliveryStatus.SUCCEEDED);
        DeliveryStateMachine machine = new DeliveryStateMachine();

        assertThatThrownBy(() -> machine.transition(task, DeliveryStatus.RETRYING))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("SUCCEEDED -> RETRYING");
    }

    @Test
    void deadEventCanReenterDispatchingForManualReplay() {
        EventRecord event = new EventRecord();
        event.setStatus(EventStatus.DEAD);

        new EventStateMachine().transition(event, EventStatus.DISPATCHING);

        assertThat(event.getStatus()).isEqualTo(EventStatus.DISPATCHING);
    }
}
