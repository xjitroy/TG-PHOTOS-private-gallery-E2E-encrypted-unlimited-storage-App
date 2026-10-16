package app.televault.domain;

/** Export selection is independent of presentation filters and automatic backup eligibility. */
public final class CloudExportFilter {
    private CloudExportFilter() {}
    public static boolean matches(String mime, int categories, int selectedKinds, int selectedCategories, long time, long from, long until) {
        int kind=mime.startsWith("image/")?1:mime.startsWith("video/")?2:mime.startsWith("audio/")?4:8;
        return (selectedKinds==0 || (selectedKinds&kind)!=0) && (selectedCategories==0 || (selectedCategories&categories)!=0)
            && ((from==0 && until==0) || time>0) && (from==0 || time>=from) && (until==0 || time<until);
    }
}
