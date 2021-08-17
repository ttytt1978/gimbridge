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
public class TwoCameraStreamFrameMerger extends AbstractFrameMerger {

    private double grabRatio = 0.36;
    private double maxFrameRatio = 0.66;
    private int maxFrameSum;


    private class ChannelFrame {
        public Frame frame;
        public int channel;

        public ChannelFrame(Frame frame, int channel) {
            this.frame = frame;
            this.channel = channel;
        }
    }


    public TwoCameraStreamFrameMerger() {
        super(15);
    }

    public TwoCameraStreamFrameMerger(int capacity) {
        super(capacity);
    }

    private synchronized boolean canGrab() {
        synchronized (frameQue) {
            int size = frameQue.size();
            if (size * 1.0 / bufferMaxCapacity >= grabRatio)
                return false;
            return true;
        }
    }


    //从通道帧列表中按顺序分离出全部的通道视频帧列表
    private List<ChannelFrame> splitVideoChannelFramesFromList(List<ChannelFrame> channelFrames) {
        List<ChannelFrame> videoFrames = new ArrayList<>();
        for (int i = channelFrames.size() - 1; i >= 0; i--) {
            ChannelFrame frame = channelFrames.get(i);
            if (frame.frame.image != null) {
                videoFrames.add(frame);
                channelFrames.remove(i);
            }
        }
        Collections.reverse(videoFrames);
        return videoFrames;
    }

    //修改给定一组通道帧的时间戳至给定的时间戳范围内
    private List<Frame> updateFrameTimestamps(List<Frame> frames, long startTimestamp, long endTimestamp) {
        if (frames.isEmpty())
            return frames;
        long oldTotalDistance = frames.get(frames.size() - 1).timestamp - frames.get(0).timestamp;
        long newTotalDistance = endTimestamp - startTimestamp;
        if (oldTotalDistance <= 0 || newTotalDistance <= 0) {
            for (Frame frame : frames)
                frame.timestamp = startTimestamp;
            return frames;
        }
        long oldLastTimestamp = frames.get(0).timestamp;
        long newLastTimestamp = startTimestamp;
        for (int i = 0; i < frames.size(); i++) {
            Frame curFrame = frames.get(i);
            newLastTimestamp = (long) ((((curFrame.timestamp - oldLastTimestamp) * 1.0 / oldTotalDistance * 1.0) * newTotalDistance) + newLastTimestamp);
            oldLastTimestamp = curFrame.timestamp;
            curFrame.timestamp = newLastTimestamp;
        }
        return frames;
    }


    //将给定通道帧列表按起止时间戳均匀排列
    private void updateTimestampForChannelFramesList(List<ChannelFrame> channelFrames, long startTimestamp, long endTimestamp) {
        if (channelFrames.isEmpty())
            return;
        long distance = endTimestamp - startTimestamp;
        if (distance <= 0)
            return;
        long step = (long) (distance / (channelFrames.size() + 1));
        for (int i = 0; i < channelFrames.size(); i++) {
            ChannelFrame frame = channelFrames.get(i);
            frame.frame.timestamp = startTimestamp + step * (i + 1);
        }
    }


    //计算给定帧列表中起始和结束两帧之间的时间戳距离
    private long getTimestampDistanceForFrameList(List<Frame> frames) {
        if (frames == null || frames.size() < 2)
            return 0;
        return frames.get(frames.size() - 1).timestamp - frames.get(0).timestamp;
    }

    //选取最小的时间戳距离（但不能等于0）
    private long getShortestTimestampDistance(long d1, long d2) {
        if (d1 <= 0 && d2 > 0)
            return d2;
        if (d2 <= 0 && d1 > 0)
            return d1;
        if (d1 <= d2)
            return d1;
        return d2;
    }

    //选取最大的时间戳距离（但不能等于0）
    private long getLongestTimestampDistance(List<Frame> frames1, List<Frame> frames2) {
        long d1 = getTimestampDistanceForFrameList(frames1);
        long d2 = getTimestampDistanceForFrameList(frames2);
        if (d1 <= 0 && d2 > 0)
            return d2;
        if (d2 <= 0 && d1 > 0)
            return d1;
        if (d1 <= d2)
            return d2;
        return d1;
    }


    //变换每个帧列表的时间戳起点到统一时间戳
    private void transformTimestampForFrameList(List<Frame> frames, long oldLastTimestamp, long newLastTimestamp) {
        if (frames.isEmpty())
            return;
        for (Frame frame : frames) {
            frame.timestamp = frame.timestamp - oldLastTimestamp + newLastTimestamp;
        }
    }

    //截取每个帧列表的一段，使其不超过最小时间戳距离
    private List<Frame> cutFrameListInShortestTimestampDistance(List<Frame> frames, long shortestTimestamp) {
        List<Frame> resultList = new ArrayList<>();
        if (frames.isEmpty())
            return resultList;
        long startTimestamp = frames.get(0).timestamp;
        for (Frame frame : frames) {
            long d = frame.timestamp - startTimestamp;
            if (d <= shortestTimestamp)
                resultList.add(frame);
        }
        return resultList;
    }

    //按帧时间戳顺序合并两个通道帧列表并按时间戳升序
    private List<ChannelFrame> mergeAndSortTwoFrames(List<Frame> frames1, List<Frame> frames2) {
        Comparator<ChannelFrame> comparator = new Comparator<ChannelFrame>() {
            @Override
            public int compare(ChannelFrame frame1, ChannelFrame frame2) {
                if (null == frame1)
                    return 0;
                if (null == frame2)
                    return 0;
                long timestamp1 = frame1.frame.timestamp;
                long timestamp2 = frame2.frame.timestamp;
                return Long.compare(timestamp1, timestamp2);
            }
        };
        List<ChannelFrame> resultList = new ArrayList<ChannelFrame>();
        for (Frame frame : frames1)
            resultList.add(new ChannelFrame(frame, 1));
        for (Frame frame : frames2)
            resultList.add(new ChannelFrame(frame, 2));
        resultList.sort(comparator);
        System.out.println("MMMMMMMMMMMMM----------合帧后的时间戳顺序：");
        for (ChannelFrame frame : resultList) {
            System.out.println("(" + frame.channel + "," + frame.frame.timestamp + "," + getFrameTypeInfo(frame.frame) + ")  ");
        }
        return resultList;
    }


    private String getFrameTypeInfo(Frame frame) {
        if (null == frame)
            return "null帧";
        if (frame.samples != null && frame.image != null)
            return "音视频帧";
        if (frame.samples != null)
            return "音频帧";
        return "视频帧";
    }


    //判断给定一组帧列表中是否有音频帧，有则返回true
    private boolean haveAudioFrameInFrameList(List<Frame> frameList) {
        for (Frame frame : frameList)
            if (frame != null && frame.samples != null)
                return true;
        return false;
    }

    //合帧处理
    @Override
    protected void mergeFrameRepeatly(List<IFrameTaker> frameTakers) {
        Comparator<Frame> frameComparator = new Comparator<Frame>() {
            @Override
            public int compare(Frame frame1, Frame frame2) {
                if (null == frame1)
                    return -1;
                if (null == frame2)
                    return 1;
                if (frame2.timestamp > frame1.timestamp)
                    return -1;
                if (frame2.timestamp == frame1.timestamp)
                    return 0;
                return 1;
            }
        };
        maxFrameSum = (int) (bufferMaxCapacity * maxFrameRatio);
        TwoVideoFrameMerger videoMerger = new TwoVideoFrameMerger();
        TwoAudioFrameMerger audioMerger = new TwoAudioFrameMerger();
        TimeUtil totalTimer = new TimeUtil();
        totalTimer.start();
        int mergeCount = 0;
        deleteCount = 0;
        List<Frame> totalFrames1 = new ArrayList<>();
        List<Frame> totalFrames2 = new ArrayList<>();
        boolean isFirstFrame1 = true, isFirstFrame2 = true;
        long oldLastTimestamp1 = 0, newLastTimestamp1 = 0, oldLastTimestamp2 = 0, newLastTimestamp2 = 0;
        while (!isStopped()) {
            TimeUtil timer = new TimeUtil();
            timer.start();
            if (!canGrab())
                continue;
            if (totalFrames1.size() < maxFrameSum) {
                IFrameTaker taker1 = frameTakers.get(0);
                List<Frame> frames1 = taker1.takeFrames();
                totalFrames1.addAll(frames1);
            }
            if (totalFrames2.size() < maxFrameSum) {
                IFrameTaker taker2 = frameTakers.get(1);
                List<Frame> frames2 = taker2.takeFrames();
                totalFrames2.addAll(frames2);
            }
//            if (!((totalFrames1.size() + totalFrames2.size() >= maxFrameSum) && ((totalFrames1.size() > maxFrameSum / 2) || (totalFrames2.size() > maxFrameSum / 2))))
//                continue;
            if (!((totalFrames1.size() + totalFrames2.size() >= maxFrameSum) && (totalFrames1.size() > maxFrameSum / 3)))
                continue;
            Collections.sort(totalFrames1, frameComparator);
            Collections.sort(totalFrames2, frameComparator);
            //积累了一定数量的帧且至少某通道中有一组帧列表
            if (isFirstFrame1 && (!totalFrames1.isEmpty()))//初始化通道1的最新时间戳
            {
                isFirstFrame1 = false;
                Frame firstFrame1 = totalFrames1.get(0);
                oldLastTimestamp1 = firstFrame1.timestamp;
            }
            if (isFirstFrame2 && (!totalFrames2.isEmpty()))//初始化通道2的最新时间戳
            {
                isFirstFrame2 = false;
                Frame firstFrame2 = totalFrames2.get(0);
                oldLastTimestamp2 = firstFrame2.timestamp;
            }

            long start1 = totalFrames1.get(0).timestamp;
            long end1 = totalFrames1.get(totalFrames1.size() - 1).timestamp;
            //修改通道2的帧时间戳，使其与通道1的时间戳在同一个时间段内
            totalFrames2 = updateFrameTimestamps(totalFrames2, start1, end1);
            audioMerger.setNeedMerged(haveAudioFrameInFrameList(totalFrames1) && haveAudioFrameInFrameList(totalFrames2));
            List<ChannelFrame> mergedFrames = mergeAndSortTwoFrames(totalFrames1, totalFrames2);//按统一时间戳顺序合并两个通道帧序列
            totalFrames1.clear();
            totalFrames2.clear();
            Frame mergedVideoFrame = null;
            for (ChannelFrame channelFrame : mergedFrames)//逐个处理每个帧
            {
                TimeUtil timer2 = new TimeUtil();
                timer2.start();
//                if (channelFrame.channel == 1 && channelFrame.frame.image != null) {
//                    Frame frame = channelFrame.frame.clone();
//                    frame.timestamp = channelFrame.frame.timestamp;
//                    addMergedFrameToQue(frame);
//                }

//                if (channelFrame.channel == 2 && channelFrame.frame.samples != null)//通道2中的音频帧
//                {
//                    addMergedFrameToQue(channelFrame.frame);
//                    continue;
//                }



//                if (channelFrame.channel == 1 && channelFrame.frame.samples != null)//通道1中的音频帧
//                {
//                    try {
//                        TimeUtil timera = new TimeUtil();
//                        timera.start();
//                        long timestamp=channelFrame.frame.timestamp;
//                        ChannelFrame nextFrame=findNextAudioChannelFrame(channelFrame,mergedFrames);
//                        long nextTimestamp=mergedFrames.get(mergedFrames.size()-1).frame.timestamp;
//                        if(nextFrame!=null)
//                            nextTimestamp=nextFrame.frame.timestamp;
//                        Buffer[] buffers=channelFrame.frame.samples;
//                        int sum=0;
//                        for(Buffer each:buffers)
//                        {
//                            sum+=each.limit();
//                        }
//                        int sampleRate=channelFrame.frame.sampleRate;
//                        System.out.println("AAAAAAA1----curTimestamp="+timestamp+" ,nextTimestamp="+nextTimestamp+" ,sampleRate="+sampleRate+" ,samples="+sum);
////                        asFilter = new FFmpegFrameFilter("aecho=0.8:0.88:90:0.4,asettb=2*intb,anull", 1);
////                        asFilter.start();
//                        Frame audioFrame = channelFrame.frame.clone();
//                        audioFrame.timestamp=timestamp;
//                        asFilter.push(audioFrame);
//                        audioFrame=asFilter.pull();
//                        if(audioFrame!=null)
//                        {
//                            buffers=audioFrame.samples;
//                            sum=0;
//                            for(Buffer each:buffers)
//                            {
//                                sum+=each.limit();
//                            }
//                           sampleRate=audioFrame.sampleRate;
//                            System.out.println("AAAAAAA2----curTimestamp="+timestamp+" ,nextTimestamp="+nextTimestamp+" ,ResampleRate="+sampleRate+" ,Resamples="+sum);
//                            long filteredTimestamp=audioFrame.timestamp;
//                            audioFrame=audioFrame.clone();
//                            audioFrame.timestamp=timestamp;
//                            addMergedFrameToQue(audioFrame);
//                            timera.end();
//                            System.out.println("AAAAAAA3------filter one audio frame...Oldtimestamp="+timestamp+" ,filteredtimestamp="+filteredTimestamp+" ,cost="+timera.getTimeInMillSecond()+"毫秒" );
//                        }
//                    } catch (FrameFilter.Exception e) {
//                        e.printStackTrace();
//                    }
//
//                }


//                if(channelFrame.frame.samples!=null&& audioFrameController.canGrab()==false)
//                {
//                    continue;
//                }
//                if(channelFrame.frame.image!=null&& videoFrameController.canGrab()==false)
//                {
//                    continue;
//                }
//                if(channelFrame.frame.samples!=null)
//                    audioFrameController.updateGrabCount();
//                if(channelFrame.frame.image!=null)
//                    videoFrameController.updateGrabCount();


                if (channelFrame.channel == 1 && channelFrame.frame.image != null)//通道1中的视频帧
                {
                    videoMerger.updateBigImage(channelFrame.frame);
                }
                if (channelFrame.channel == 2 && channelFrame.frame.image != null)//通道2中的视频帧
                {
                    videoMerger.updateSmallImage(channelFrame.frame);
                }
                if (channelFrame.channel == 1 &&channelFrame.frame.image != null)//合成新的视频帧
                {
                    try {
                        mergedVideoFrame = videoMerger.mergeFrame(channelFrame.frame.timestamp);
                    } catch (Exception e) {
                    }
                    timer2.end();
                    if (mergedVideoFrame != null) {
                        System.out.println("------------合并处理一帧视频..timestamp=" + mergedVideoFrame.timestamp + " ,cost=" + (long) (timer2.getTimeInMillSecond()) + "毫秒");
                        mergeCount++;
                    }
                    addMergedFrameToQue(mergedVideoFrame);

                    totalTimer.end();
                    if (totalTimer.getTimeInSecond() >= 1.0) {
                        System.out.println("1秒内合成视频总帧数：" + mergeCount + " ,删除视频总帧数：" + deleteCount);
                        mergeCount = 0;
                        deleteCount = 0;
                        totalTimer = new TimeUtil();
                        totalTimer.start();
                    }
                }
                if (channelFrame.channel == 1 && channelFrame.frame.samples != null)//通道1中的音频帧
                {
                    Frame audioFrame = audioMerger.mergeFrame(channelFrame.frame, 1);
                    if (audioFrame != null)
                        addMergedFrameToQue(audioFrame);
                }
                if (channelFrame.channel == 2 && channelFrame.frame.samples != null)//通道2中的音频帧
                {
                    Frame audioFrame = audioMerger.mergeFrame(channelFrame.frame, 2);
                    if (audioFrame != null)
                        addMergedFrameToQue(audioFrame);
                }

//                if (channelFrame.channel == 1 && channelFrame.frame.samples != null)//通道1中的音频帧
//                {
//                    Frame audioFrame = channelFrame.frame.clone();
//                    audioFrame.timestamp = channelFrame.frame.timestamp;
//                    addMergedFrameToQue(audioFrame);
//                }
//                if (channelFrame.channel == 2 && channelFrame.frame.samples != null)//通道2中的音频帧
//                {
//                    Frame audioFrame = channelFrame.frame.clone();
//                    audioFrame.timestamp = channelFrame.frame.timestamp;
//                    addMergedFrameToQue(audioFrame);
//                }


            }
            System.out.println("合帧器完成工作一次...bufferSize=" + frameQue.size());

//
//            for(Frame frame:totalFrames1)
//            {
//                if(frame!=null)
//                {
//                    addMergedFrameToQue(frame);
////                System.out.println("合帧器完成工作一次...bufferSize="+frameQue.size());
//                }
//            }

        }
    }


    private class TwoVideoFrameMerger {
        private Java2DFrameConverter converter = new Java2DFrameConverter();
        private Java2DFrameConverter converter2 = new Java2DFrameConverter();
        private BufferedImage previousBigImage = null;
        private BufferedImage previousSmallImage = null;
        private double scaleRation = 0.56;


        public TwoVideoFrameMerger() {
            previousBigImage=createBlankImage();
            previousSmallImage=createBlankImage();
        }

        public Frame mergeFrame(long timestamp) {
            Frame resultFrame = null;
            if (null != previousBigImage && null != previousSmallImage) {
                converter = new Java2DFrameConverter();
                BufferedImage image1 = scaleImage(previousSmallImage, scaleRation, previousBigImage.getWidth(), previousBigImage.getHeight());
                BufferedImage imageb = copyImage(previousBigImage);
                BufferedImage image2 = combineTwoImagesToLeftTop(image1, imageb);
                resultFrame = converter.convert(image2);
                resultFrame.timestamp = timestamp;
                System.out.println("-----------------           ------push one combined video frame（实时大小图像合成一帧）...." + timestamp);
                return resultFrame;
            }
            return resultFrame;
        }

        public void updateSmallImage(Frame frame2) {
            converter2 = new Java2DFrameConverter();
            if (null != frame2)
                previousSmallImage = converter2.getBufferedImage(frame2);
        }

        public void updateBigImage(Frame frame1) {
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
            graphics.draw3DRect(0, 0, smallImage.getWidth(), smallImage.getHeight(), true);
            graphics.dispose();
            return bigImage;
        }

        //将小图像叠加到大图像上合成一个新图像返回
        private BufferedImage combineTwoImagesToRightTop(BufferedImage smallImage, BufferedImage bigImage) {
            Graphics2D graphics = bigImage.createGraphics();
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER));
            //叠加图像
            int marginx = (int) (bigImage.getWidth() * 0.03);
            int marginy = (int) (bigImage.getHeight() * 0.03);
            int x = bigImage.getWidth() - smallImage.getWidth() - marginx;
            int y = marginy;
            graphics.drawImage(smallImage, x, y, smallImage.getWidth(), smallImage.getHeight(), null);
            graphics.setColor(Color.WHITE);
            graphics.setStroke(new BasicStroke((int) (bigImage.getWidth() * 0.03)));
            graphics.draw3DRect(x, y, smallImage.getWidth(), smallImage.getHeight(), true);
            graphics.dispose();
            return bigImage;
        }


        //将小图像叠加到大图像上合成一个新图像返回
        private BufferedImage combineTwoImagesToLeftTop(BufferedImage smallImage, BufferedImage bigImage) {
            Graphics2D graphics = bigImage.createGraphics();
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER));
            //叠加图像
            int marginx = (int) (bigImage.getWidth() * 0.03);
            int marginy = (int) (bigImage.getHeight() * 0.03);
            int x = marginx;
            int y = marginy;
            graphics.drawImage(smallImage, x, y, smallImage.getWidth(), smallImage.getHeight(), null);
            graphics.setColor(Color.WHITE);
            graphics.setStroke(new BasicStroke((int) (bigImage.getWidth() * 0.03)));
            graphics.draw3DRect(x, y, smallImage.getWidth(), smallImage.getHeight(), true);
            graphics.dispose();
            return bigImage;
        }

        //复制一个同样内容的图像
        private BufferedImage copyImage(BufferedImage bufImg) {

            int width = bufImg.getWidth();
            int height = bufImg.getHeight();
            int type = bufImg.getType();
            System.out.println("----------------------------------------------------- copy bufferedimage type:" + type);
            BufferedImage resultImg = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
//            BufferedImage resultImg = createBufferedImage(bufImg);
            Graphics2D graphics = resultImg.createGraphics();
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER));
            //设置图片背景
            graphics.drawImage(bufImg, 0, 0, null);
            graphics.dispose();
            return resultImg;
        }

        //复制一个同样内容的图像
        private BufferedImage createBlankImage() {
            BufferedImage resultImg = new BufferedImage(480, 360, BufferedImage.TYPE_3BYTE_BGR);
//            BufferedImage resultImg = createBufferedImage(bufImg);
            Graphics2D graphics = resultImg.createGraphics();
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER));
            //设置图像背景
           graphics.setBackground(Color.BLACK);
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


    private class TwoAudioFrameMerger {
//        private String filterStr = "[0:a]aformat=sample_fmts=s16:channel_layouts=FL:sample_rates=48000[audio1];[1:a]aformat=sample_fmts=s16:channel_layouts=FR:sample_rates=48000[audio2];[audio1][audio2]amix=inputs=2[mix1];[mix1]atempo=1.08[a]";

//        private String filterStr = "[0:a]aformat=sample_fmts=s16:channel_layouts=FL:sample_rates=48000[audio1];[1:a]aformat=sample_fmts=s16:channel_layouts=FR:sample_rates=48000[audio2];[audio1][audio2]amix=inputs=2:duration=shortest[a]";
        //        private String filterStr = "[0:a][1:a]amix=inputs=2[a]";
        private String filterStr = "[0:a]aformat=sample_fmts=s16:channel_layouts=FL[audio1];[1:a]aformat=sample_fmts=s16:channel_layouts=FR[audio2];[audio1][audio2]amix=inputs=2:duration=shortest[a]";
        private FFmpegFrameFilter filter;
        private boolean needMerged = true;


        public TwoAudioFrameMerger() {
            try {
                needMerged = true;
                filter = new FFmpegFrameFilter(filterStr, 1);
                filter.setSampleRate(44100);
                filter.setAudioInputs(2);
                filter.start();
            } catch (FrameFilter.Exception e) {
                throw new RuntimeException("FFmpegFrameFilter created error:" + e.getMessage());
            }
        }

        public void setNeedMerged(boolean needMerged) {
            this.needMerged = needMerged;
        }

        public Frame mergeFrame(Frame audioFrame, int channelNumber) {
            Frame resultFrame = null;
            try {
                if (needMerged == false) {
                    System.out.println("AAAAAAA------mixed one audio frame..." + audioFrame.timestamp);
                    return audioFrame;
                }
                TimeUtil timer = new TimeUtil();
                timer.start();
                filter.push(channelNumber - 1, audioFrame);
                resultFrame = filter.pullSamples();
                if (resultFrame != null) {
                    resultFrame = resultFrame.clone();
                    resultFrame.timestamp = audioFrame.timestamp;
                    timer.end();
                    System.out.println("AAAAAAA------mixed two audio frames..." + resultFrame.timestamp + " ,cost=" + timer.getTimeInMillSecond() + "毫秒");
                }
                return resultFrame;
            } catch (Exception e) {
                return null;
            }
        }
    }


}
