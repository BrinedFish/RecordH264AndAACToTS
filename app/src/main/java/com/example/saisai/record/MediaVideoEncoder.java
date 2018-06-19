package com.example.saisai.record;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.support.v4.app.NavUtils;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Created by saisai on 2018/6/5 0005.
 */

public class MediaVideoEncoder extends MediaEncoder{

    private static final boolean DEBUG=true;
    private static final String TAG="MediaVideoEncoder";

    private static final String MIME_TYPE="video/avc";
    //parameters for recording
    private static final int FRAME_RATE=25;
    private static final float BPP=0.25f;

    private final int mWidth;
    private final int mHeight;

    private byte[] mFrameData =new byte[CameraWrapper.IMAGE_WIDTH*CameraWrapper.IMAGE_HEIGHT*3/2];


    public MediaVideoEncoder(final MediaMuxerWrapper muxer, final MediaEncoderListener listener,final int width,final int height) {
        super(muxer, listener);
        if(DEBUG) Log.i(TAG,"MediaVideoEncoder: ");
        mWidth=width;
        mHeight=height;
    }

    public boolean frameAvailableSoon(final byte[] input){
        encodeFrame(input);
        return true;
    }

    private void encodeFrame(byte[] input) {
        Log.i(TAG,"encodeFrame()");
        long encodeSize=0;
        NV21toI420SemiPlanar(input,mFrameData,this.mWidth,this.mHeight);

        final ByteBuffer buf=ByteBuffer.wrap(mFrameData);
        encode(buf,mFrameData.length,getPTSUs());
        frameAvailableSoon();

    }

    private void NV21toI420SemiPlanar(byte[] nv21bytes, byte[] i420bytes, int width, int height) {
        System.arraycopy(nv21bytes,0,i420bytes,0,width*height);
        for(int i=width*height;i<nv21bytes.length;i+=2){
            i420bytes[i]=nv21bytes[i+1];
            i420bytes[i+1]=nv21bytes[i];
        }
    }

    @Override
    void prepare() throws IOException {
        if(DEBUG) Log.i(TAG,"prepare:");
        mTrackIndex=-1;
        mMuxerStarted=mIsEOS=false;
        final MediaCodecInfo videoCodecInfo=selectVideoCodec(MIME_TYPE);
        if(videoCodecInfo== null){
            Log.e(TAG,"Unable to find an appropriate codec for " + MIME_TYPE);
            return;
        }
        if(DEBUG) Log.i(TAG,"selected codec: " + videoCodecInfo.getName());

        final MediaFormat format=MediaFormat.createVideoFormat(MIME_TYPE,mWidth,mHeight);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE,calcBitRate());
        format.setInteger(MediaFormat.KEY_FRAME_RATE,FRAME_RATE);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,10);
        if(DEBUG) Log.i(TAG,"format: "+format);

        mMediaCodec= MediaCodec.createEncoderByType(MIME_TYPE);
        mMediaCodec.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);
        //get surface for encoder input
        //this method only can call between #configure adn #readyStart
        mMediaCodec.start();
        if(DEBUG) Log.i(TAG,"prepare finishing");
        if(mListener!=null){
            try {
                mListener.onPrepared(this);
            }catch (final Exception e){
                Log.e(TAG,"prepare:",e);
            }
        }
    }

    private int calcBitRate() {
        final int bitrate=(int)(BPP*FRAME_RATE*mWidth*mHeight);
        Log.i(TAG,String.format("bitrate = %5.2f[Mbps]",bitrate/1024f/1024f));
        return 0;
    }

    @Override
    protected void release() {
        if(DEBUG) Log.i(TAG,"release:");
        super.release();
    }

    /**
     * select the first codec that metch a specific MIME type
     * @param mimeType
     * @return null if no codec metched
     */
    protected static final MediaCodecInfo selectVideoCodec(final String mimeType) {

        if(DEBUG) Log.v(TAG,"selectVideoCodec:");
        //get the list of available codecs
        final  int numCodecs= MediaCodecList.getCodecCount();
        for(int i=0;i<numCodecs;i++){
            final MediaCodecInfo codecInfo=MediaCodecList.getCodecInfoAt(i);
            if(!codecInfo.isEncoder()){//skipp decoder
                continue;
            }
            //select first codec that match a specific MIME type and color format
            final String[] types=codecInfo.getSupportedTypes();
            for(int j=0;j<types.length;j++){
                if(types[j].equalsIgnoreCase(mimeType)){
                    if(DEBUG) Log.i(TAG,"codec:"+codecInfo.getName()+",MIME="+types[j]);
                    final int format=selectColorFormat(codecInfo,mimeType);
                    if(format>0){
                        return codecInfo;
                    }
                }
            }

        }
        return null;
    }

    /**
     * select color format available on specific codec and we can use
     * @param codecInfo
     * @param mimeType
     * @return 0 if no colorFormat is matched
     */
    protected static int selectColorFormat(final MediaCodecInfo codecInfo, final String mimeType) {
        if(DEBUG) Log.i(TAG,"selectColorFormat:");
        int result=0;
        final MediaCodecInfo.CodecCapabilities caps;
        try {
            Thread.currentThread().setPriority(Thread.MAX_PRIORITY);
            caps=codecInfo.getCapabilitiesForType(mimeType);
        }finally {
            Thread.currentThread().setPriority(Thread.NORM_PRIORITY);
        }
        int colorFormat;
        for(int i=0;i<caps.colorFormats.length;i++){
            colorFormat=caps.colorFormats[i];
            if(isRecognizedViewoFormat(colorFormat)){
                if(result==0)
                    result=colorFormat;
                break;
            }
        }
        if(result==0)
            Log.e(TAG, "couldn't find a good color format for " + codecInfo.getName() + " / " + mimeType);
        return result;
    }

    protected static int[] recognizedFormats;
    static {
        recognizedFormats=new int[]{
//        	MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,
//        	MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
//        	MediaCodecInfo.CodecCapabilities.COLOR_QCOM_FormatYUV420SemiPlanar,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
        };
    }

    private static boolean isRecognizedViewoFormat(final int colorFormat) {
        if(DEBUG) Log.i(TAG,"isRecognizedViewoFormat:colorFormat=" + colorFormat);
        final int n=recognizedFormats!=null?recognizedFormats.length:0;
        for(int i=0;i<n;i++){
            if(recognizedFormats[i]==colorFormat){
                return true;
            }
        }
        return false;
    }

    @Override
    protected void signalEndOfInputStream() {
        if(DEBUG) Log.d(TAG,"sending EOS to encoder");
        mMediaCodec.signalEndOfInputStream();
        mIsEOS=true;
    }
}