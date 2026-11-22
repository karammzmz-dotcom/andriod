package com.catacombs.game;

import android.app.Activity;
import android.os.Bundle;
import android.view.*;
import android.graphics.*;
import android.content.Context;
import android.content.res.AssetManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.widget.*;
import android.graphics.Typeface;
import java.io.*;
import java.util.*;
import org.json.*;

public class MainActivity extends Activity {
    static { System.loadLibrary("catacombs"); }
    private static native int[] nativeFrame(int keys);
    private static native boolean nativeLoadHex(String path);
    private static native void nativeStopHex();
    private static native short[] nativeDrainAudio(int samples);
    private static native void nativeResetGame();
    private static native void nativeSetAudioEnabled(boolean enabled);
    private FrameLayout gameHost;
    private View launcher;
    private GameView gameView;
    private boolean playing = false;
    private boolean soundEnabled = true;
    private final ArrayList<GameEntry> games = new ArrayList<>();
    private final ArrayList<View> launcherCards = new ArrayList<>();
    private int selectedIndex = 0;

    private static class GameEntry {
        String title, description, asset, license;
        boolean nativeCatacombs;
        GameEntry(String t,String d,String a,String l,boolean c){title=t;description=d;asset=a;license=l;nativeCatacombs=c;}
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        loadOfflineLibrary();
        showLauncher();
    }

    private void loadOfflineLibrary() {
        games.clear();
        games.add(new GameEntry("CATACOMBS OF THE DAMNED", "Original native Android port · dungeon crawler", "", "MIT", true));
        try {
            InputStream in=getAssets().open("games.json");
            ByteArrayOutputStream out=new ByteArrayOutputStream(); byte[] b=new byte[8192]; int n;
            while((n=in.read(b))>0) out.write(b,0,n); in.close();
            JSONArray arr=new JSONArray(out.toString("UTF-8"));
            File dir=new File(getFilesDir(),"arduboy"); dir.mkdirs();
            for(int i=0;i<arr.length();i++){
                JSONObject o=arr.getJSONObject(i); String file=o.getString("file");
                File dest=new File(dir,file);
                if(!dest.exists()){
                    try(InputStream src=getAssets().open("games/"+file); FileOutputStream dst=new FileOutputStream(dest)){
                        byte[] buf=new byte[8192];int r;while((r=src.read(buf))>0)dst.write(buf,0,r);
                    }
                }
                games.add(new GameEntry(o.optString("title",file),o.optString("description","Arduboy game"),dest.getAbsolutePath(),o.optString("license",""),false));
            }
        } catch(Exception e) { /* Keep the built-in Catacombs entry available if the optional catalogue is absent. */ }
    }

    private void showLauncher() {
        playing=false;
        if(gameView!=null){gameView.stop();gameView=null;}
        nativeStopHex(); nativeResetGame();
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(Color.BLACK); root.setPadding(dp(18),dp(24),dp(18),dp(12));
        TextView title=new TextView(this); title.setText("ARDUBOY"); title.setTextColor(Color.WHITE); title.setTextSize(30); title.setTypeface(Typeface.MONOSPACE,Typeface.BOLD); root.addView(title);
        TextView sub=new TextView(this); sub.setText("BLACK & WHITE  /  OFFLINE LIBRARY"); sub.setTextColor(0xffaaaaaa); sub.setTextSize(11); sub.setTypeface(Typeface.MONOSPACE); root.addView(sub);
        TextView count=new TextView(this); count.setText(games.size()+" GAMES INSTALLED  ·  NO INTERNET REQUIRED"); count.setTextColor(0xff888888); count.setTextSize(10); count.setTypeface(Typeface.MONOSPACE); LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2);cp.topMargin=dp(14);cp.bottomMargin=dp(8);root.addView(count,cp);
        ScrollView scroll=new ScrollView(this); LinearLayout list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);launcherCards.clear();selectedIndex=0;
        for(int i=0;i<games.size();i++){
            final GameEntry g=games.get(i);
            LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(12),dp(10),dp(12),dp(10));card.setBackgroundColor(0xff101010);
            TextView name=new TextView(this);name.setText(String.format(Locale.US,"%02d  /  %s",i+1,g.title.toUpperCase(Locale.ROOT)));name.setTextColor(Color.WHITE);name.setTextSize(14);name.setTypeface(Typeface.MONOSPACE,Typeface.BOLD);card.addView(name);
            TextView desc=new TextView(this);desc.setText(g.description+(g.license.isEmpty()?"":"  ·  "+g.license));desc.setTextColor(0xff999999);desc.setTextSize(10);desc.setTypeface(Typeface.MONOSPACE);LinearLayout.LayoutParams dp=new LinearLayout.LayoutParams(-1,-2);dp.topMargin=dp(4);card.addView(desc,dp);
            card.setFocusable(true);card.setFocusableInTouchMode(false);card.setClickable(true);card.setOnClickListener(v->launch(g));card.setOnFocusChangeListener((v,focused)->v.setBackgroundColor(focused?0xff303030:0xff101010));launcherCards.add(card);
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(7);list.addView(card,p);
        }
        scroll.addView(list);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        TextView footer=new TextView(this);footer.setText("D-PAD / STICK TO BROWSE  ·  A TO LAUNCH  ·  LICENCES");footer.setTextColor(0xff777777);footer.setTextSize(9);footer.setTypeface(Typeface.MONOSPACE);footer.setPadding(0,dp(10),0,0);footer.setOnClickListener(v->showLicenses());footer.setFocusable(true);footer.setFocusableInTouchMode(false);footer.setOnFocusChangeListener((v,focused)->footer.setTextColor(focused?Color.WHITE:0xff777777));root.addView(footer);launcherCards.add(footer);
        root.setFocusableInTouchMode(true);root.requestFocus();root.setOnKeyListener((v,key,event)->{if(event.getAction()==KeyEvent.ACTION_DOWN&&(key==KeyEvent.KEYCODE_DPAD_CENTER||key==KeyEvent.KEYCODE_ENTER||key==KeyEvent.KEYCODE_BUTTON_A)){return true;}return false;});
        launcher=root;setContentView(root);if(!launcherCards.isEmpty())launcherCards.get(0).requestFocus();
    }

    private void showLicenses(){
        String msg="Arduous emulator core copyright James Roeder and contributors · GPL-3.0. No warranty; you may share and modify the GPL-covered program under GPL version 3. The complete licence is bundled in this app and can be viewed below.\n\nCatacombs of the Damned: James Howard · MIT. Additional offline ROMs include their catalogue author, declared SPDX licence, source path, and licence text in the bundled credits.\n\nKeyboard: arrows/WASD, Z/Enter=A, X=B, M=toggle sound. Controllers: D-pad/stick, A/B, Start=toggle sound. No touchscreen gameplay controls."
        ;new android.app.AlertDialog.Builder(this).setTitle("ABOUT / LICENCES").setMessage(msg).setNeutralButton("VIEW GPL-3.0",(d,w)->showGplText()).setPositiveButton("CLOSE",null).show();
    }
    private void showGplText(){
        try{InputStream in=getAssets().open("licenses/Arduous-GPL-3.0.txt");ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;while((n=in.read(b))>0)out.write(b,0,n);in.close();TextView text=new TextView(this);text.setText(out.toString("UTF-8"));text.setTextColor(Color.WHITE);text.setTextSize(11);text.setTypeface(Typeface.MONOSPACE);text.setPadding(dp(14),dp(12),dp(14),dp(12));ScrollView scroll=new ScrollView(this);scroll.setBackgroundColor(Color.BLACK);scroll.addView(text);new android.app.AlertDialog.Builder(this).setTitle("GNU GPL VERSION 3").setView(scroll).setPositiveButton("CLOSE",null).show();}catch(Exception e){Toast.makeText(this,"GPL text is missing from this build",Toast.LENGTH_LONG).show();}
    }

    private void launch(GameEntry entry) {
        if(!entry.nativeCatacombs&&!nativeLoadHex(entry.asset)){Toast.makeText(this,"Could not start this Arduboy ROM",Toast.LENGTH_SHORT).show();return;}
        if(entry.nativeCatacombs){nativeStopHex();nativeResetGame();}
        playing=true;gameHost=new FrameLayout(this);gameHost.setBackgroundColor(Color.BLACK);gameView=new GameView(this,entry.nativeCatacombs);gameHost.addView(gameView);setContentView(gameHost);gameView.setFocusable(true);gameView.requestFocus();gameView.start();
    }
    private int dp(float v){return (int)(v*getResources().getDisplayMetrics().density+0.5f);}
    @Override public boolean dispatchKeyEvent(KeyEvent event){
        if(!playing&&event.getAction()==KeyEvent.ACTION_DOWN&&!launcherCards.isEmpty()){
            int k=event.getKeyCode();
            if(k==KeyEvent.KEYCODE_DPAD_DOWN||k==KeyEvent.KEYCODE_DPAD_RIGHT){selectedIndex=Math.min(launcherCards.size()-1,selectedIndex+1);launcherCards.get(selectedIndex).requestFocus();return true;}
            if(k==KeyEvent.KEYCODE_DPAD_UP||k==KeyEvent.KEYCODE_DPAD_LEFT){selectedIndex=Math.max(0,selectedIndex-1);launcherCards.get(selectedIndex).requestFocus();return true;}
            if(k==KeyEvent.KEYCODE_ENTER||k==KeyEvent.KEYCODE_DPAD_CENTER||k==KeyEvent.KEYCODE_BUTTON_A||k==KeyEvent.KEYCODE_BUTTON_X){if(selectedIndex<games.size())launch(games.get(selectedIndex));else showLicenses();return true;}
        }
        return super.dispatchKeyEvent(event);
    }
    @Override public void onBackPressed(){if(playing)showLauncher();else super.onBackPressed();}
    @Override public boolean onKeyDown(int keyCode,KeyEvent event){
        if(playing&&keyCode==KeyEvent.KEYCODE_BACK){showLauncher();return true;}
        if(!playing&&event.getAction()==KeyEvent.ACTION_DOWN){
            if(keyCode==KeyEvent.KEYCODE_DPAD_DOWN||keyCode==KeyEvent.KEYCODE_DPAD_RIGHT){selectedIndex=Math.min(launcherCards.size()-1,selectedIndex+1);if(!launcherCards.isEmpty())launcherCards.get(selectedIndex).requestFocus();return true;}
            if(keyCode==KeyEvent.KEYCODE_DPAD_UP||keyCode==KeyEvent.KEYCODE_DPAD_LEFT){selectedIndex=Math.max(0,selectedIndex-1);if(!launcherCards.isEmpty())launcherCards.get(selectedIndex).requestFocus();return true;}
            if(keyCode==KeyEvent.KEYCODE_ENTER||keyCode==KeyEvent.KEYCODE_DPAD_CENTER||keyCode==KeyEvent.KEYCODE_BUTTON_A||keyCode==KeyEvent.KEYCODE_BUTTON_X){if(selectedIndex>=0&&selectedIndex<games.size())launch(games.get(selectedIndex));else showLicenses();return true;}
        }
        return super.onKeyDown(keyCode,event);
    }

    private class GameView extends SurfaceView implements Runnable {
        private final boolean catacombs;
        private final SurfaceHolder holder; private final HashSet<Integer> held=new HashSet<>(); private volatile boolean running; private Thread thread,audioThread; private AudioTrack audioTrack;
        private final Bitmap bitmap=Bitmap.createBitmap(128,64,Bitmap.Config.ARGB_8888);private final Paint paint=new Paint();private int axisMask=0;
        GameView(Context c,boolean isCatacombs){super(c);catacombs=isCatacombs;holder=getHolder();paint.setFilterBitmap(false);setFocusable(true);}
        void start(){if(running)return;running=true;nativeSetAudioEnabled(soundEnabled);thread=new Thread(this,"ArduboyFrame");thread.start();startAudio();}
        void startAudio(){int min=AudioTrack.getMinBufferSize(44100,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT);audioTrack=new AudioTrack(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),new AudioFormat.Builder().setSampleRate(44100).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),Math.max(min,8192),AudioTrack.MODE_STREAM,0 /* AudioManager.AUDIO_SESSION_ID_GENERATE */);audioTrack.play();audioThread=new Thread(()->{while(running){short[] pcm=nativeDrainAudio(735);if(pcm!=null&&audioTrack!=null)audioTrack.write(pcm,0,pcm.length,AudioTrack.WRITE_BLOCKING);}} ,"ArduboyAudio");audioThread.start();}
        void stop(){running=false;if(thread!=null)try{thread.join(500);}catch(InterruptedException ignored){}if(audioThread!=null)try{audioThread.join(500);}catch(InterruptedException ignored){}if(audioTrack!=null){try{audioTrack.pause();audioTrack.flush();audioTrack.release();}catch(Exception ignored){}audioTrack=null;}}
        private int mask(){int m=axisMask;for(int k:held)m|=keyMask(k);return m;}
        private int keyMask(int k){switch(k){case KeyEvent.KEYCODE_DPAD_LEFT:case KeyEvent.KEYCODE_A:return 1;case KeyEvent.KEYCODE_DPAD_RIGHT:case KeyEvent.KEYCODE_D:return 2;case KeyEvent.KEYCODE_DPAD_UP:case KeyEvent.KEYCODE_W:return 4;case KeyEvent.KEYCODE_DPAD_DOWN:case KeyEvent.KEYCODE_S:return 8;case KeyEvent.KEYCODE_Z:case KeyEvent.KEYCODE_ENTER:case KeyEvent.KEYCODE_SPACE:case KeyEvent.KEYCODE_BUTTON_A:case KeyEvent.KEYCODE_BUTTON_X:return 16;case KeyEvent.KEYCODE_X:case KeyEvent.KEYCODE_ESCAPE:case KeyEvent.KEYCODE_BUTTON_B:case KeyEvent.KEYCODE_BUTTON_Y:return 32;}return 0;}
        @Override public boolean onKeyDown(int k,KeyEvent e){if(k==KeyEvent.KEYCODE_BACK)return false;if(k==KeyEvent.KEYCODE_M||k==KeyEvent.KEYCODE_BUTTON_START){soundEnabled=!soundEnabled;nativeSetAudioEnabled(soundEnabled);return true;}if(keyMask(k)!=0){held.add(k);return true;}return super.onKeyDown(k,e);}
        @Override public boolean onKeyUp(int k,KeyEvent e){if(keyMask(k)!=0){held.remove(k);return true;}return super.onKeyUp(k,e);}
        @Override public boolean onGenericMotionEvent(MotionEvent e){if((e.getSource()&InputDevice.SOURCE_JOYSTICK)==InputDevice.SOURCE_JOYSTICK&&e.getAction()==MotionEvent.ACTION_MOVE){float x=e.getAxisValue(MotionEvent.AXIS_X),y=e.getAxisValue(MotionEvent.AXIS_Y),hx=e.getAxisValue(MotionEvent.AXIS_HAT_X),hy=e.getAxisValue(MotionEvent.AXIS_HAT_Y);if(Math.abs(hx)>0.4f)x=hx;if(Math.abs(hy)>0.4f)y=hy;axisMask=0;if(x<-.45f)axisMask|=1;if(x>.45f)axisMask|=2;if(y<-.45f)axisMask|=4;if(y>.45f)axisMask|=8;return true;}return super.onGenericMotionEvent(e);}
        @Override public boolean onTouchEvent(MotionEvent e){return true;}
        @Override public void run(){final long frameNs=1_000_000_000L/(catacombs?30:60);long next=System.nanoTime();while(running){int[] px=nativeFrame(mask());bitmap.setPixels(px,0,128,0,0,128,64);if(holder.getSurface().isValid()){Canvas c=null;try{c=holder.lockCanvas();if(c!=null){c.drawColor(Color.BLACK);float s=Math.min(c.getWidth()/128f,c.getHeight()/64f),w=128*s,h=64*s,l=(c.getWidth()-w)/2f,t=(c.getHeight()-h)/2f;c.drawBitmap(bitmap,null,new RectF(l,t,l+w,t+h),paint);}}finally{if(c!=null)holder.unlockCanvasAndPost(c);}}next+=frameNs;long wait=next-System.nanoTime();if(wait>0)try{Thread.sleep(wait/1_000_000L,(int)(wait%1_000_000L));}catch(InterruptedException ignored){}else next=System.nanoTime();}}
    }
}
