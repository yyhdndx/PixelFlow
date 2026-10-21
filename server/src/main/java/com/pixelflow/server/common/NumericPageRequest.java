package com.pixelflow.server.common;

import org.springframework.util.MultiValueMap;

/** Strict decimal pagination for new endpoints; existing endpoints keep their contracts. */
public record NumericPageRequest(int count, Long cursor) {
    public static NumericPageRequest parse(MultiValueMap<String,String> params,String cursorName) {
        String count=single(params,"count"), cursor=single(params,cursorName);
        return new NumericPageRequest(count==null ? 20 : (int)positive(count,50),
                cursor==null ? null : positive(cursor,Long.MAX_VALUE));
    }

    private static String single(MultiValueMap<String,String> params,String name) {
        var values=params.get(name);
        if (values==null) return null;
        if (values.size()!=1 || values.get(0)==null) throw invalid();
        return values.get(0);
    }

    private static long positive(String text,long maximum) {
        if (text.isEmpty()) throw invalid();
        long value=0;
        for (int i=0;i<text.length();i++) {
            int digit=text.charAt(i)-'0';
            if (digit<0 || digit>9 || value>(maximum-digit)/10) throw invalid();
            value=value*10+digit;
        }
        if (value<1 || value>maximum) throw invalid();
        return value;
    }

    private static ApiException invalid() {
        return new ApiException(400,"INVALID_ARGUMENT","count 应为 1–50，游标应为正整数，分页参数不能重复");
    }
}
