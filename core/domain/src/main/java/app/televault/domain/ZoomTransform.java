package app.televault.domain;

/** Allocation-free focal zoom and bounded viewport pan. All units are display pixels. */
public final class ZoomTransform {
    private final float maximum;
    private float width=1, height=1, scale=1, x, y;
    public ZoomTransform(float maximum) {
        if (!Float.isFinite(maximum) || maximum<1 || maximum>32) throw new IllegalArgumentException("Invalid zoom limit");
        this.maximum=maximum;
    }
    public float scale() { return scale; }
    public float x() { return x; }
    public float y() { return y; }
    public void viewport(float width,float height) {
        if (!Float.isFinite(width)||!Float.isFinite(height)||width<=0||height<=0||width>1000000||height>1000000) return;
        this.width=width;this.height=height;clamp();
    }
    public void reset() { scale=1;x=0;y=0; }
    public void transform(float factor,float focusX,float focusY,float dx,float dy) {
        if(!Float.isFinite(factor)||factor<=0||!Float.isFinite(focusX)||!Float.isFinite(focusY)||!Float.isFinite(dx)||!Float.isFinite(dy))return;
        float next=Math.max(1,Math.min(maximum,scale*factor));
        float ratio=next/scale;
        x=(focusX-width/2)*(1-ratio)+x*ratio+dx;
        y=(focusY-height/2)*(1-ratio)+y*ratio+dy;
        scale=next;clamp();
    }
    private void clamp() {
        float maxX=width*(scale-1)/2,maxY=height*(scale-1)/2;
        x=Math.max(-maxX,Math.min(maxX,x));y=Math.max(-maxY,Math.min(maxY,y));
        if(scale==1){x=0;y=0;}
    }
}
