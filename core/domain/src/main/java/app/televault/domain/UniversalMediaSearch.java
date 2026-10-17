package app.televault.domain;

import java.time.*;
import java.util.*;
import java.util.regex.*;

/** Locale-safe literal matching, with optional inclusive calendar dates; no network or SQL. */
public final class UniversalMediaSearch {
    private UniversalMediaSearch() { }
    private static final Pattern DATE = Pattern.compile("(?<![0-9])([0-9]{4}-[0-9]{1,2}-[0-9]{1,2}|[0-9]{1,2}[/.-][0-9]{1,2}[/.-][0-9]{4})(?![0-9])");
    public record Query(String text, LocalDate start, LocalDate end, boolean valid) { }
    public static String digits(String value) {
        StringBuilder out = new StringBuilder();
        for (int i=0;i<value.length();i++) { char c=value.charAt(i); int n=Character.digit(c,10);out.append(n>=0?(char)('0'+n):c); }
        return out.toString();
    }
    public static LocalDate date(String value) {
        String text=digits(value).trim(); if(text.isEmpty()) return null;
        String[] parts=text.split("[-/\\.]"); if(parts.length!=3) throw new IllegalArgumentException("Use day/month/year or year-month-day");
        try { return parts[0].length()==4?LocalDate.of(Integer.parseInt(parts[0]),Integer.parseInt(parts[1]),Integer.parseInt(parts[2])):LocalDate.of(Integer.parseInt(parts[2]),Integer.parseInt(parts[1]),Integer.parseInt(parts[0])); }
        catch (RuntimeException error) { throw new IllegalArgumentException("Invalid date",error); }
    }
    public static Query parse(String text, String start, String end) {
        try {
            String clean=digits(text); Matcher matcher=DATE.matcher(clean);List<LocalDate> dates=new ArrayList<>();StringBuffer rest=new StringBuffer();
            while(matcher.find()) { dates.add(date(matcher.group()));matcher.appendReplacement(rest," "); } matcher.appendTail(rest);
            if(dates.size()>2) return new Query("",null,null,false);
            LocalDate from=date(start),to=date(end);
            if(from==null && !dates.isEmpty()) from=dates.get(0);
            if(to==null && !dates.isEmpty()) to=dates.get(dates.size()-1);
            if(from!=null && to==null) to=from;
            if(to!=null && from==null) from=to;
            if(from!=null && from.isAfter(to)) return new Query("",from,to,false);
            return new Query(rest.toString().trim().toLowerCase(Locale.ROOT),from,to,true);
        } catch (IllegalArgumentException error) { return new Query("",null,null,false); }
    }
    public static boolean matches(Query query,String name,String metadata,long timestamp,ZoneId zone) {
        if(!query.valid()) return false;
        if(query.start()!=null) { if(timestamp<=0)return false;LocalDate date=Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate();if(date.isBefore(query.start()) || date.isAfter(query.end()))return false; }
        String haystack=digits(name+" "+metadata).toLowerCase(Locale.ROOT);
        for(String token:query.text().split("\\s+")) if(!token.isEmpty() && !haystack.contains(token)) return false;
        return true;
    }
}
