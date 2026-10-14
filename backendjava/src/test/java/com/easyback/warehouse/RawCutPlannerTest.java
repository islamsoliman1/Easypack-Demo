package com.easyback.warehouse;

import com.easyback.identity.ApiException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RawCutPlannerTest {
    BigDecimal d(String value) { return new BigDecimal(value); }
    void same(String value,BigDecimal actual) { assertEquals(0,d(value).compareTo(actual)); }
    @Test void lengthHasNoKerfAndEvenTheSmallestPositiveRemnantReturns() {
        var p=RawCutPlanner.plan("LENGTH",d("240.251"),d("60"),d("120.125"),d("60"),2,1,false,true,true);
        same("240.250",p.outputAmount()); same("0.001",p.returnedAmount()); same("0",p.scrapAmount());
        same("0.001",p.remainders().getFirst().lengthMm());
    }
    @Test void areaGridPartitionsSourceWithoutOverlapAndSeparatesActualScrap() {
        var p=RawCutPlanner.plan("AREA",d("300"),d("200"),d("100"),d("60"),4,2,false,true,false);
        same("24000",p.outputAmount()); same("20000",p.returnedAmount()); same("16000",p.scrapAmount());
        assertEquals(2,p.remainders().size()); same("60000",p.outputAmount().add(p.returnedAmount()).add(p.scrapAmount()));
    }
    @Test void rotationCanFitButAreaAloneDoesNotProveFit() {
        assertThrows(ApiException.class,()->RawCutPlanner.plan("AREA",d("150"),d("240"),d("120"),d("60"),4,2,false,true,true));
        var p=RawCutPlanner.plan("AREA",d("150"),d("240"),d("120"),d("60"),4,2,true,true,true);
        same("60",p.outputLengthMm()); same("120",p.outputWidthMm()); same("7200",p.returnedAmount());
    }
    @Test void exactFitsHaveNoRemnantAndInvalidGridsAreRejected() {
        var p=RawCutPlanner.plan("AREA",d("200"),d("120"),d("100"),d("60"),4,2,false,true,true);
        assertTrue(p.remainders().isEmpty()); same("0",p.scrapAmount());
        assertThrows(ApiException.class,()->RawCutPlanner.plan("AREA",d("500"),d("500"),d("100"),d("60"),3,2,false,true,true));
        assertThrows(ApiException.class,()->RawCutPlanner.plan("LENGTH",d("500"),null,d("100"),null,2,2,false,true,true));
    }
}
