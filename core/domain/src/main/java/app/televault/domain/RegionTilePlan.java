package app.televault.domain;

/** Pure planning runs only when a gesture settles, never inside drawing or native decoder locks. */
public final class RegionTilePlan {
    private RegionTilePlan() { }
    public record Plan(int left,int top,int right,int bottom,int visibleLeft,int visibleTop,int visibleRight,int visibleBottom,int sample) { }
    public static Plan create(int imageWidth,int imageHeight,int viewWidth,int viewHeight,boolean sideways,float zoom,float centerX,float centerY,long byteBudget) {
        if(imageWidth<=0||imageHeight<=0||viewWidth<=0||viewHeight<=0||byteBudget<4||!Float.isFinite(zoom)||!Float.isFinite(centerX)||!Float.isFinite(centerY))throw new IllegalArgumentException("Invalid region geometry");
        double fit=Math.min((double)(sideways?viewHeight:viewWidth)/imageWidth,(double)(sideways?viewWidth:viewHeight)/imageHeight);
        double factor=fit*Math.max(1,Math.min(20,zoom));
        double halfW=Math.min(imageWidth/2.0,(sideways?viewHeight:viewWidth)/factor/2);
        double halfH=Math.min(imageHeight/2.0,(sideways?viewWidth:viewHeight)/factor/2);
        double x=Math.max(halfW,Math.min(imageWidth-halfW,centerX)),y=Math.max(halfH,Math.min(imageHeight-halfH,centerY));
        int left=(int)Math.max(0,Math.floor(x-halfW*1.25)),top=(int)Math.max(0,Math.floor(y-halfH*1.25));
        int right=(int)Math.min(imageWidth,Math.ceil(x+halfW*1.25)),bottom=(int)Math.min(imageHeight,Math.ceil(y+halfH*1.25));
        int sample=1;
        while(sample<(1<<30)) {
            long w=((long)right-left+sample-1)/sample,h=((long)bottom-top+sample-1)/sample;
            if(w<=viewWidth*2L&&h<=viewHeight*2L&&w*h<=byteBudget/4)break;
            sample*=2;
        }
        return new Plan(left,top,right,bottom,(int)Math.floor(x-halfW),(int)Math.floor(y-halfH),(int)Math.ceil(x+halfW),(int)Math.ceil(y+halfH),sample);
    }
}
