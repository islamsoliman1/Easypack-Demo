package com.easyback.warehouse;

import static com.easyback.warehouse.RawData.*;
import com.easyback.identity.ApiException;
import java.math.BigDecimal;
import java.util.*;

public final class RawCutPlanner {
    private RawCutPlanner() {}
    private static void fit(boolean valid) { if (!valid) throw new ApiException(409,"RAW_CUT_DOES_NOT_FIT","Cut layout does not fit the source."); }
    public static BigDecimal amount(String unit,BigDecimal length,BigDecimal width) { return unit.equals("AREA") ? length.multiply(width) : length; }
    public static Plan plan(String unit,BigDecimal length,BigDecimal width,BigDecimal targetLength,BigDecimal targetWidth,
        int quantity,int columns,boolean rotated,boolean returnFirst,boolean returnSecond) {
        fit(quantity>0 && quantity<=200 && columns>0 && columns<=quantity && quantity%columns==0 && targetLength!=null);
        boolean area=unit.equals("AREA");
        fit(area ? width!=null && targetWidth!=null : columns==1 && !rotated);
        BigDecimal outLength=rotated ? targetWidth : targetLength, outWidth=rotated ? targetLength : targetWidth;
        int rows=quantity/columns;
        BigDecimal usedLength=outLength.multiply(BigDecimal.valueOf(rows));
        BigDecimal usedWidth=area ? outWidth.multiply(BigDecimal.valueOf(columns)) : width;
        fit(usedLength.compareTo(length)<=0 && (!area || usedWidth.compareTo(width)<=0));
        var remainders=new ArrayList<Remainder>();
        BigDecimal end=length.subtract(usedLength);
        if(end.signum()>0) remainders.add(new Remainder(1,end,width,amount(unit,end,width),returnFirst));
        // Guillotine layout partitions the source into a filled grid and two non-overlapping rectangles.
        if(area) {
            BigDecimal side=width.subtract(usedWidth);
            if(side.signum()>0) remainders.add(new Remainder(2,usedLength,side,amount(unit,usedLength,side),returnSecond));
        }
        BigDecimal returned=BigDecimal.ZERO,scrap=BigDecimal.ZERO;
        for(var r:remainders) if(r.returned()) returned=returned.add(r.amount()); else scrap=scrap.add(r.amount());
        return new Plan(unit,amount(unit,length,width),outLength,outWidth,quantity,rows,columns,
            area ? usedLength.multiply(usedWidth) : usedLength,returned,scrap,List.copyOf(remainders));
    }
}
