package com.coursetrace.video;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class ByteRangeTest {
    @Test void clampsHugeEndsAndSuffixesWithoutOverflow() {
        assertThat(ByteRange.parse("bytes=8-999999999999999999999999",10)).isEqualTo(new ByteRange(8,2,true));
        assertThat(ByteRange.parse("bytes=-999999999999999999999999",10)).isEqualTo(new ByteRange(0,10,true));
        assertThat(ByteRange.parse("bytes=0-0",10)).isEqualTo(new ByteRange(0,1,true));
    }
    @ParameterizedTest
    @ValueSource(strings={"bytes=10-","bytes=999999999999999999999999-","bytes=-0","bytes=5-2","bytes=a-b","bytes=","bytes=--1"})
    void rejectsUnsatisfiableOrMalformedRanges(String range) {
        assertThatThrownBy(()->ByteRange.parse(range,10)).isInstanceOf(IllegalArgumentException.class);
    }
}
