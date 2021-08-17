package com.md.ffmpeg;

import com.md.api.IFrameTaker;
import com.md.util.TimeUtil;
import org.bytedeco.javacv.FFmpegFrameFilter;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameFilter;
import org.bytedeco.javacv.Java2DFrameConverter;
import sun.font.FontDesignMetrics;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.*;

//简单合帧器
public class TwoStreamOneAudioFrameMerger1 extends AbstractFrameMerger {
    private long delayTime = 0;

    public TwoStreamOneAudioFrameMerger1() {
        super(200);
    }

    public TwoStreamOneAudioFrameMerger1(int capacity) {
        super(capacity);
    }

    private class ChannelFrame
    {
        public Frame frame;
        public int   channel;
        public ChannelFrame(Frame frame,int channel)
        {
            this.frame=frame;
            this.channel=channel;
        }
    }

    //合帧处理
    @Override
    protected void mergeFrameRepeatly(List<IFrameTaker> frameTakers) {

        try {
            IFrameTaker taker1 = frameTakers.get(0);
            IFrameTaker taker2 = frameTakers.get(1);
            String filterStr = "[0:a]aformat=sample_fmts=s16:channel_layouts=FL:sample_rates=48000[audio1];[1:a]aformat=sample_fmts=s16:channel_layouts=FR:sample_rates=48000[audio2];[audio1][audio2]amix=inputs=2[a]";
            FFmpegFrameFilter filter2 =new FFmpegFrameFilter("scale=600:600",600,400);
            filter2.start();
            FFmpegFrameFilter filter =new FFmpegFrameFilter(filterStr,1);
            filter.setAudioInputs(2);
            filter.start();
            String filterStra = "aformat=sample_fmts=s16:channel_layouts=mono:sample_rates=48000[a1];[a1]anull";
            FFmpegFrameFilter filter3 =new FFmpegFrameFilter(filterStra,1);
            filter3.start();
            Frame frame=null,frame2=null;
            while(!isStopped())
            {
                if(frame==null)
                {
                    frame=taker1.takeFirstFrame();
                    if(frame==null)
                    {
//                        System.out.println("---------------no frame grabbed..." );
                        continue;
                    }
                }

                if(frame2==null)
                {
                    frame2=taker2.takeFirstFrame();
                    if(frame2==null)
                    {
//                        System.out.println("---------------no frame2 grabbed..." );
                        continue;
                    }
                }

                if(frame.samples!=null)
                {
//                Frame mixedAudioFrame=frameFormat.format(frame);
//                if(mixedAudioFrame!=null)
//                {
//                    mixedAudioFrame=frameFormat.format(frame);
//                    recorder.setTimestamp(frame.timestamp);
//                        recorder.recordSamples(mixedAudioFrame.samples);
//                        System.out.println("AAAAAAA---------------mixed two audio frames..." );
//                }
                    filter.push(0,frame);
                    Frame mixedAudioFrame=filter.pullSamples();
                    if(mixedAudioFrame!=null)
                    {
//                    filter3.push(mixedAudioFrame);
//                    mixedAudioFrame=filter3.pullSamples();
                        if(mixedAudioFrame!=null)
                        {
                            mixedAudioFrame.timestamp=frame.timestamp;
                           addMergedFrameToQue(mixedAudioFrame);
                            System.out.println("AAAAAAA1111---------------mixed two audio frames..." );
                        }

                    }
                }
                if(frame2.samples!=null)
                {
                    frame2.timestamp=frame.timestamp;
                    filter.push(1,frame2);
                    Frame mixedAudioFrame=filter.pullSamples();
                    if(mixedAudioFrame!=null)
                    {
//                    filter3.push(mixedAudioFrame);
//                    mixedAudioFrame=filter3.pullSamples();
                        if(mixedAudioFrame!=null)
                        {
                            mixedAudioFrame.timestamp=frame.timestamp;
                            addMergedFrameToQue(mixedAudioFrame);
                            System.out.println("AAAAAAA222222---------------mixed two audio frames..." );
                        }

                    }
                }
                if(frame.image!=null)
                {
                    filter2.push(frame);
                    Frame mergedFrame=filter2.pullImage();
                    if(mergedFrame!=null)
                    {
                        mergedFrame.timestamp=frame.timestamp;
                        addMergedFrameToQue(mergedFrame);
                        System.out.println("VVVV---------------mixed two video frames..." );
                    }
                }

                frame=null;
                frame2=null;
            }

        } catch (FrameFilter.Exception e) {
            throw new RuntimeException(e.getMessage());
        }

    }

    //修改给定一组通道帧的时间戳至给定的时间戳范围内
    private List<Frame> updateFrameTimestamps(List<Frame> frames,long startTimestamp,long endTimestamp)
    {
        if(frames.isEmpty())
            return frames;
        long oldTotalDistance=frames.get(frames.size()-1).timestamp-frames.get(0).timestamp;
        long newTotalDistance=endTimestamp-startTimestamp;
        if(oldTotalDistance<=0||newTotalDistance<=0)
        {
            for(Frame frame:frames)
                frame.timestamp=startTimestamp;
            return frames;
        }
        long oldLastTimestamp=frames.get(0).timestamp;
        long newLastTimestamp=startTimestamp;
        for(int i=0;i<frames.size();i++)
        {
            Frame curFrame=frames.get(i);
            newLastTimestamp=(long)((((curFrame.timestamp-oldLastTimestamp)*1.0/oldTotalDistance*1.0)*newTotalDistance)+newLastTimestamp);
            oldLastTimestamp=curFrame.timestamp;
            curFrame.timestamp=newLastTimestamp;
        }
        return frames;
    }

    //混合连个通道的音频帧
    private List<Frame> mixTwoAudioFrames(List<Frame> frames1,List<Frame> frames2,FFmpegFrameFilter filter)
    {
//        String filterStr = "[0:a]aformat=sample_fmts=s16:channel_layouts=mono:sample_rates=48000[audio1];[1:a]aformat=sample_fmts=s16:channel_layouts=mono:sample_rates=48000[audio2];[audio1][audio2]amix=inputs=2[a]";
//        String filterStr = "[0:a]aformat=sample_fmts=s16:channel_layouts=mono:sample_rates=48000[audio1];[1:a]aformat=sample_fmts=s16:channel_layouts=mono:sample_rates=48000[audio2];[audio1]anull[a]";
        String filterStr = "[0:a]aformat=sample_fmts=s16:channel_layouts=mono:sample_rates=48000[audio1];[1:a]aformat=sample_fmts=s16:channel_layouts=mono:sample_rates=48000[audio2];[audio1][audio2]amix=inputs=2[a]";

        filter =new FFmpegFrameFilter(filterStr,1);
        filter.setAudioInputs(2);
        try {
            filter.start();
        } catch (FrameFilter.Exception e) {
            e.printStackTrace();
        }
        List<Frame> resultList= new ArrayList<>();
        while(frames1.isEmpty()==false&&frames2.isEmpty()==false)
        {
            try {
            Frame frame1=frames1.remove(0);
                filter.push(0,frame1);
                Frame mixedFrame=filter.pullSamples();
                if(mixedFrame!=null&&mixedFrame.samples!=null)
                {
                    mixedFrame.timestamp=frame1.timestamp;
                    resultList.add(mixedFrame);
                    System.out.println("AAAAAAA1111---------------mixed two audio frames..." );
                }
                Frame frame2=frames2.remove(0);
                filter.push(1,frame2);
                mixedFrame=filter.pullSamples();
                if(mixedFrame!=null&&mixedFrame.samples!=null)
                {
                    mixedFrame.timestamp=frame1.timestamp;
                    resultList.add(mixedFrame);
                    System.out.println("AAAAAAA22222---------------mixed two audio frames..." );
                }

            } catch (FrameFilter.Exception e) {
                e.printStackTrace();
            }

        }
//        if(frames1.isEmpty()==false)
//            resultList.addAll(frames1);
        return resultList;
    }

    //按帧时间戳顺序合并两个通道帧列表
    private List<ChannelFrame> mergeTwoFrames(List<Frame> frames1,List<Frame> frames2)
    {
        List<ChannelFrame> frames=new ArrayList<ChannelFrame>();
        while(frames1.isEmpty()==false&&frames2.isEmpty()==false)
        {
            Frame frame1=frames1.get(0);
            Frame frame2=frames2.get(0);
            if(frame1.timestamp<=frame2.timestamp)
            {
                frames.add(new ChannelFrame(frame1,1));
                frames1.remove(0);
            }else
            {
                frames.add(new ChannelFrame(frame2,2));
                frames2.remove(0);
            }
        }
        while(frames1.isEmpty()==false)
        {
            Frame frame1=frames1.get(0);
            frames.add(new ChannelFrame(frame1,1));
            frames1.remove(0);
        }
       while (frames2.isEmpty()==false)
       {
           Frame frame2=frames2.get(0);
           frames.add(new ChannelFrame(frame2,2));
           frames2.remove(0);
       }
        return frames;
    }



    //从帧列表中按原有顺序抽取出全部音频帧(原列表不变）
    private List<Frame> copyAudioFrameListFrom(List<Frame> frames)
    {
        List<Frame> resultList=new ArrayList<Frame>();
        for(Frame frame:frames)
            if(null!=frame&&frame.samples!=null)
                resultList.add(frame);
        return resultList;
    }

    //从帧列表中按原有顺序抽取出全部视频帧(原列表不变）
    private List<Frame> copyVideoFrameListFrom(List<Frame> frames)
    {
        List<Frame> resultList=new ArrayList<Frame>();
        for(Frame frame:frames)
            if(null!=frame&&frame.image!=null)
                resultList.add(frame);
        return resultList;
    }

    //按帧时间戳顺序合并两个通道帧列表并按时间戳升序
    private List<ChannelFrame> mergeAndSortTwoFrames(List<Frame> frames1,List<Frame> frames2)
    {
        Comparator<ChannelFrame> comparator=new Comparator<ChannelFrame>() {
            @Override
            public int compare(ChannelFrame frame1, ChannelFrame frame2) {
                if(null==frame1)
                    return 0;
                if(null==frame2)
                    return 0;
               long timestamp1=frame1.frame.timestamp;
               long timestamp2=frame2.frame.timestamp;
               if(timestamp1==timestamp2)
                   return 0;
               if(timestamp1>timestamp2)
                   return 1;
               return -1;
            }
        };
        List<ChannelFrame> resultList=new ArrayList<ChannelFrame>();
        for(Frame frame:frames1)
            resultList.add(new ChannelFrame(frame,1));
        for(Frame frame:frames2)
            resultList.add(new ChannelFrame(frame,2));
        Collections.sort(resultList,comparator);
        return resultList;

    }

    private class TwoAudioFrameMerger{
        private  String filterStr = "[0:a][1:a]amix=inputs=2[a]";
        private String filterStr8 = "[0:a][1:a]amerge[a]";
        private  FFmpegFrameFilter filter;
        private Frame frame1;
        private Frame frame2;

        public TwoAudioFrameMerger(int audioChannels)  {
            try {
                frame1=null;
                frame2=null;
                filter =new FFmpegFrameFilter(filterStr,audioChannels);
                filter.setAudioInputs(2);
                filter.start();
            } catch (FrameFilter.Exception e) {
               throw new RuntimeException("FFmpegFrameFilter created error:"+e.getMessage());
            }
        }

        public void updateBigAudio(Frame frame1)
        {
            this.frame1=frame1;
        }

        public void updateSmallAudio(Frame frame2)
        {
            this.frame2=frame2;
        }

        public Frame mergeFrame(long timestamp) {
            Frame resultFrame = null;
            if(frame1==null||frame2==null)
                return resultFrame;
            try {
                filter.push(0,frame1);
                filter.push(1,frame2);
                resultFrame = filter.pullSamples();
                if(resultFrame!=null)
                {
                   resultFrame.timestamp=frame1.timestamp;
                    System.out.println("AAAAAAA---------------mixed two audio frames..."+timestamp );
                    frame1=null;
                    frame2=null;
                }
                return resultFrame;
            } catch (Exception e) {
              return null;
            }
        }

        public Frame mergeFrame(Frame audioFrame,int channelNumber) {
            Frame resultFrame = null;
            try {
                TimeUtil timer = new TimeUtil();
                timer.start();
                filter.push(channelNumber-1,audioFrame);
                resultFrame = filter.pullSamples();
                if(resultFrame!=null)
                {
                    frame1=null;
                    frame2=null;
                    resultFrame.timestamp=audioFrame.timestamp;
                    timer.end();
                    System.out.println("AAAAAAA------mixed two audio frames..."+resultFrame.timestamp+" ,cost="+timer.getTimeInMillSecond()+"毫秒" );
                }
                return resultFrame;
            } catch (Exception e) {
                return null;
            }
        }
    }


    private class TwoVideoFrameMerger {
        private Java2DFrameConverter converter = new Java2DFrameConverter();
        private Java2DFrameConverter converter2 = new Java2DFrameConverter();
        private BufferedImage previousBigImage = null;
        private BufferedImage previousSmallImage = null;
        private double scaleRation = 0.76;


        public Frame mergeFrame(long timestamp) {
            Frame resultFrame = null;
            if(null!=previousBigImage&&null!=previousSmallImage)
            {
                converter = new Java2DFrameConverter();
                BufferedImage image1=scaleImage(previousSmallImage,scaleRation,previousBigImage.getWidth(),previousBigImage.getHeight());
                BufferedImage imageb=copyImage(previousBigImage);
                BufferedImage image2=combineTwoImagesToRightTop(image1,imageb);
                resultFrame = converter.convert(image2);
                resultFrame.timestamp = timestamp;
                System.out.println("-----------------           ------push one combined video frame（实时大小图像合成一帧）...." + timestamp);
                return  resultFrame;
            }
            return resultFrame;
        }

        public void updateSmallImage(Frame frame2)
        {
            converter2 = new Java2DFrameConverter();
            if (null != frame2)
                previousSmallImage = converter2.getBufferedImage(frame2);
        }

        public void updateBigImage(Frame frame1)
        {
            converter = new Java2DFrameConverter();
            if (null != frame1)
                previousBigImage = converter.getBufferedImage(frame1);
        }



         //复制一个同样内容但缩放过的图像（按给定宽高）
        private BufferedImage scaleImage(BufferedImage bufImg, double ratio, int maxWidth, int maxHeight) {
            int width = (int) (maxWidth * ratio);
            int height = (int) (maxHeight * ratio);
//           return (BufferedImage) bufImg.getScaledInstance(width,height,Image.SCALE_FAST);
            BufferedImage resultImg = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);

            Graphics2D graphics = resultImg.createGraphics();
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER));
            //设置图片背景
            graphics.drawImage(bufImg, 0, 0, width, height, null);
            graphics.dispose();
            return resultImg;
        }

        //将小图像叠加到大图像上合成一个新图像返回
        private BufferedImage combineTwoImages(BufferedImage smallImage, BufferedImage bigImage) {
            Graphics2D graphics = bigImage.createGraphics();
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER));
            //叠加图像
            graphics.drawImage(smallImage, 0, 0, smallImage.getWidth(), smallImage.getHeight(), null);
            graphics.setColor(Color.RED);
            graphics.setStroke(new BasicStroke(15));
            graphics.draw3DRect(0,0,smallImage.getWidth(),smallImage.getHeight(),true);
            graphics.dispose();
            return bigImage;
        }

        //将小图像叠加到大图像上合成一个新图像返回
        private  BufferedImage combineTwoImagesToRightTop(BufferedImage smallImage, BufferedImage bigImage) {
            Graphics2D graphics = bigImage.createGraphics();
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER));
            //叠加图像
            int marginx=(int)(bigImage.getWidth()*0.03);
            int marginy=(int)(bigImage.getHeight()*0.03);
            int x=bigImage.getWidth()-smallImage.getWidth()-marginx;
            int y=marginy;
            graphics.drawImage(smallImage,x,y, smallImage.getWidth(), smallImage.getHeight(), null);
            graphics.setColor(Color.WHITE);
            graphics.setStroke(new BasicStroke((int)(bigImage.getWidth()*0.03)));
            graphics.draw3DRect(x,y,smallImage.getWidth(),smallImage.getHeight(),true);
            graphics.dispose();
            return bigImage;
        }

        //复制一个同样内容的图像
        private BufferedImage copyImage(BufferedImage bufImg) {

            int width = bufImg.getWidth();
            int height = bufImg.getHeight();
            int type=bufImg.getType();
            System.out.println("----------------------------------------------------- copy bufferedimage type:"+type);
            BufferedImage resultImg = new BufferedImage(width,height,BufferedImage.TYPE_3BYTE_BGR);
//            BufferedImage resultImg = createBufferedImage(bufImg);
            Graphics2D graphics = resultImg.createGraphics();
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER));
            //设置图片背景
            graphics.drawImage(bufImg, 0, 0, null);
            graphics.dispose();
            return resultImg;
        }

        private BufferedImage createBufferedImage(BufferedImage src) {
            ColorModel cm = src.getColorModel();
            BufferedImage image = new BufferedImage(
                    cm,
                    cm.createCompatibleWritableRaster(src.getWidth(), src.getHeight()),
                    cm.isAlphaPremultiplied(),
                    null);
            return image;
        }


        private BufferedImage addSubtitle(BufferedImage bufImg, String subTitleContent) {

            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            // 添加字幕时的时间
            Font font = new Font("微软雅黑", Font.BOLD, 18);
            String timeContent = sdf.format(new Date());
            FontDesignMetrics metrics = FontDesignMetrics.getMetrics(font);
            BufferedImage resultImg = bufImg;
            Graphics2D graphics = resultImg.createGraphics();
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER));

            //设置图片背景
//            graphics.drawImage(previousSmallImage, 0, 0, 100,100, null);
            //设置左上方时间显示
            graphics.setColor(Color.BLUE);
            graphics.setFont(font);
            graphics.drawString(timeContent, 0, metrics.getAscent());

            // 计算文字长度，计算居中的x点坐标
//        int textWidth = metrics.stringWidth(subTitleContent);
//        int widthX = (bufImg.getWidth() - textWidth) / 2;
//        graphics.setColor(Color.red);
//        graphics.setFont(font);
//        graphics.drawString(subTitleContent, widthX, bufImg.getHeight() - 100);
            graphics.dispose();
            return resultImg;
        }


        public BufferedImage toBufferedImage(Image image) {
            if (image instanceof BufferedImage) {
                return (BufferedImage) image;
            }

            // 此代码确保在图像的所有像素被载入
            image = new ImageIcon(image).getImage();
            // 如果图像有透明用这个方法
//		boolean hasAlpha = hasAlpha(image);

            // 创建一个可以在屏幕上共存的格式的bufferedimage
            BufferedImage bimage = null;
            GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
            try {
                //确定新的缓冲图像类型的透明度
                int transparency = Transparency.OPAQUE;
                //if (hasAlpha) {
                transparency = Transparency.BITMASK;
                //}

                // 创造一个bufferedimage
                GraphicsDevice gs = ge.getDefaultScreenDevice();
                GraphicsConfiguration gc = gs.getDefaultConfiguration();
                bimage = gc.createCompatibleImage(
                        image.getWidth(null), image.getHeight(null), transparency);
            } catch (HeadlessException e) {
                // 系统不会有一个屏幕
            }

            if (bimage == null) {
                // 创建一个默认色彩的bufferedimage
                int type = BufferedImage.TYPE_INT_RGB;
                //int type = BufferedImage.TYPE_3BYTE_BGR;//by wang
                //if (hasAlpha) {
                type = BufferedImage.TYPE_INT_ARGB;
                //}
                bimage = new BufferedImage(image.getWidth(null), image.getHeight(null), type);
            }

            // 把图像复制到bufferedimage上
            Graphics g = bimage.createGraphics();

            // 把图像画到bufferedimage上
            g.drawImage(image, 0, 0, null);
            g.dispose();

            return bimage;
        }


    }

}
