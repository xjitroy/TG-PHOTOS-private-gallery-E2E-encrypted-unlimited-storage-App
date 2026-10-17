package app.televault.domain;
/** A stationary five-second viewer hold; never replaces gallery selection gestures. */
public final class PhotoTextHold {
 public static final long DELAY=5000L;
 private long began;private float x,y,slop;private boolean armed;
 public void begin(long now,float x,float y,float slop) { this.began=now;this.x=x;this.y=y;this.slop=Math.max(0,slop);armed=true; }
 public void update(float x,float y,int pointers,boolean active) { float dx=x-this.x,dy=y-this.y;if(!active || pointers!=1 || !Float.isFinite(x) || !Float.isFinite(y) || dx*dx+dy*dy>slop*slop)armed=false; }
 public boolean fire(long now) { if(!armed || now<began || now-began<DELAY)return false;armed=false;return true; }
 public void cancel() { armed=false; }
}
