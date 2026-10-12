package com.partnermesh.provisioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class FaultInjectorTest {

    @Test
    void failNextIsConsumedOncePerRequest() {
        FaultInjector faults = new FaultInjector();
        faults.configure(new FaultInjector.Settings(0, 0, 2, 0));
        assertThatThrownBy(faults::beforeWork).isInstanceOf(FaultInjector.FaultInjectedException.class);
        assertThatThrownBy(faults::beforeWork).isInstanceOf(FaultInjector.FaultInjectedException.class);
        assertThatCode(faults::beforeWork).doesNotThrowAnyException();
        assertThat(faults.current().failNext()).isZero();
    }

    @Test
    void settingsAreClamped() {
        FaultInjector faults = new FaultInjector();
        faults.configure(new FaultInjector.Settings(7.5, -3, -1, -1));
        assertThat(faults.current()).isEqualTo(new FaultInjector.Settings(1.0, 0, 0, 0));
        assertThatThrownBy(faults::beforeWork).isInstanceOf(FaultInjector.FaultInjectedException.class);
    }
}
