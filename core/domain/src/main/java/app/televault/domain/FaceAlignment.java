package app.televault.domain;
/** Least-squares five-point, non-reflecting alignment used by OpenCV SFace. */
public final class FaceAlignment {
 private FaceAlignment() { }
 private static final float[] TARGET={38.2946f,51.6963f,73.5318f,51.5014f,56.0252f,71.7366f,41.5493f,92.3655f,70.7299f,92.2041f};
 public static float[] matrix(float[] source) {
  if(source==null||source.length!=10)throw new IllegalArgumentException("Invalid face landmarks");
  double sx=0,sy=0,tx=0,ty=0;
  for(int i=0;i<10;i+=2){if(!Float.isFinite(source[i])||!Float.isFinite(source[i+1]))throw new IllegalArgumentException("Invalid landmarks");sx+=source[i]/5.0;sy+=source[i+1]/5.0;tx+=TARGET[i]/5.0;ty+=TARGET[i+1]/5.0;}
  double aa=0,bb=0,denom=0;
  for(int i=0;i<10;i+=2){double x=source[i]-sx,y=source[i+1]-sy,u=TARGET[i]-tx,v=TARGET[i+1]-ty;aa+=x*u+y*v;bb+=x*v-y*u;denom+=x*x+y*y;}
  if(denom<1e-6)throw new IllegalArgumentException("Degenerate face landmarks");
  double a=aa/denom,b=bb/denom,dx=tx-a*sx+b*sy,dy=ty-b*sx-a*sy;
  double error=0;
  for(int i=0;i<10;i+=2){double x=a*source[i]-b*source[i+1]+dx-TARGET[i],y=b*source[i]+a*source[i+1]+dy-TARGET[i+1];error+=x*x+y*y;}
  if(Math.sqrt(error/5)>12)throw new IllegalArgumentException("Unreliable face alignment");
  return new float[]{(float)a,(float)-b,(float)dx,(float)b,(float)a,(float)dy,0,0,1};
 }
}
