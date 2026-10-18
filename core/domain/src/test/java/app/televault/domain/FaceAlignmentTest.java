package app.televault.domain;
import org.junit.Test;
import static org.junit.Assert.*;
public class FaceAlignmentTest {
 private final float[] target={38.2946f,51.6963f,73.5318f,51.5014f,56.0252f,71.7366f,41.5493f,92.3655f,70.7299f,92.2041f};
 @Test public void knownFivePointTransformReturnsEveryLandmarkToTheTemplate(){
  float[] source=target.clone();double angle=.4,scale=3;
  for(int i=0;i<10;i+=2){source[i]=(float)(scale*(Math.cos(angle)*target[i]-Math.sin(angle)*target[i+1])+120);source[i+1]=(float)(scale*(Math.sin(angle)*target[i]+Math.cos(angle)*target[i+1])+80);}
  var m=FaceAlignment.matrix(source);
  for(int i=0;i<10;i+=2){assertEquals(target[i],m[0]*source[i]+m[1]*source[i+1]+m[2],.001);assertEquals(target[i+1],m[3]*source[i]+m[4]*source[i+1]+m[5],.001);}
 }
 @Test(expected=IllegalArgumentException.class) public void degeneratePointsNeverCreateAnEmbedding(){FaceAlignment.matrix(new float[10]);}
 @Test(expected=IllegalArgumentException.class) public void nonFinitePointsAreRejected(){var p=target.clone();p[0]=Float.NaN;FaceAlignment.matrix(p);}
}
