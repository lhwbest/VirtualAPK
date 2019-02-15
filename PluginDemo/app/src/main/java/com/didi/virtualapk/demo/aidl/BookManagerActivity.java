package com.didi.virtualapk.demo.aidl;

import java.util.List;

import com.didi.virtualapk.demo.R;
import com.didi.virtualapk.demo.socket.TCPClientActivity;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import static android.Manifest.permission.CAMERA;
import static android.Manifest.permission.READ_CALENDAR;
import static android.Manifest.permission.WRITE_CALENDAR;

public class BookManagerActivity extends AppCompatActivity {
    public static final int REQUEST_CALL_PERMISSION = 10111;
    private static final String TAG = "BookManagerActivity";
    private static final int MESSAGE_NEW_BOOK_ARRIVED = 1;

    private IBookManager mRemoteBookManager;
    private EditText mEditText;

    @SuppressLint("HandlerLeak")
    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_NEW_BOOK_ARRIVED:
                    Log.d(TAG, "receive new book :" + msg.obj);
                    mEditText.append(msg.obj.toString() + "\n");
                    break;
                default:
                    super.handleMessage(msg);
            }
        }
    };

    private IBinder.DeathRecipient mDeathRecipient = new IBinder.DeathRecipient() {
        @Override
        public void binderDied() {
            Log.d(TAG, "binder died. tname:" + Thread.currentThread().getName());
            if (mRemoteBookManager == null)
                return;
            mRemoteBookManager.asBinder().unlinkToDeath(mDeathRecipient, 0);
            mRemoteBookManager = null;
            // TODO:这里重新绑定远程Service
        }
    };

    private ServiceConnection mConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder service) {
            IBookManager bookManager = IBookManager.Stub.asInterface(service);
            mRemoteBookManager = bookManager;
            try {
                mRemoteBookManager.asBinder().linkToDeath(mDeathRecipient, 0);
                List<Book> list = bookManager.getBookList();
                Log.i(TAG, "query book list, list type:"
                        + list.getClass().getCanonicalName());
                Log.i(TAG, "query book list:" + list.toString());
                Book newBook = new Book(3, "Android进阶");
                bookManager.addBook(newBook);
                Log.i(TAG, "add book:" + newBook);
                List<Book> newList = bookManager.getBookList();
                Log.i(TAG, "query book list:" + newList.toString());
                bookManager.registerListener(mOnNewBookArrivedListener);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }

        public void onServiceDisconnected(ComponentName className) {
            mRemoteBookManager = null;
            Log.d(TAG, "onServiceDisconnected. tname:" + Thread.currentThread().getName());
        }
    };

    private IOnNewBookArrivedListener mOnNewBookArrivedListener = new IOnNewBookArrivedListener.Stub() {

        @Override
        public void onNewBookArrived(Book newBook) throws RemoteException {
            mHandler.obtainMessage(MESSAGE_NEW_BOOK_ARRIVED, newBook)
                    .sendToTarget();
        }
    };

    private boolean host;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_book_manager);

        ContentResolver contentResolver = getContentResolver();
        Log.e(TAG, "plugin contentResolver class name : " + contentResolver.getClass().getSimpleName());
        Log.e(TAG, "plugin packageManager class name : " + getPackageManager().getClass().getSimpleName());

        mEditText = (EditText) findViewById(R.id.editText);
        Intent intent = new Intent(this, BookManagerService.class);
        bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
        host = true;
//        ActivityCompat.requestPermissions(this,new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE,Manifest.permission.RECORD_AUDIO},REQUEST_CALL_PERMISSION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(new String[]{Manifest.permission.CALL_PHONE}, REQUEST_CALL_PERMISSION);
        }
    }

    public void onButton1Click(View view) {
//        new Thread(new Runnable() {
//
//            @Override
//            public void run() {
//                if (mRemoteBookManager != null) {
//                    try {
//                        List<Book> newList = mRemoteBookManager.getBookList();
//                    } catch (RemoteException e) {
//                        e.printStackTrace();
//                    }
//                }
//            }
//        }).start();
//
//        Intent intent = new Intent(this, TCPClientActivity.class);
//        startActivity(intent);
        Intent intent = new Intent(Intent.ACTION_CALL);
        Uri data = Uri.parse("tel:10086");
        intent.setData(data);
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return;
        }
        startActivity(intent);
    }

    @Override
    protected void onDestroy() {
        if (mRemoteBookManager != null
                && mRemoteBookManager.asBinder().isBinderAlive()) {
            try {
                Log.i(TAG, "unregister listener:" + mOnNewBookArrivedListener);
                mRemoteBookManager
                        .unregisterListener(mOnNewBookArrivedListener);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
        unbindService(mConnection);
        super.onDestroy();
    }

    /**
     * 检查权限后的回调
     * @param requestCode 请求码
     * @param permissions  权限
     * @param grantResults 结果
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        switch (requestCode) {
            case REQUEST_CALL_PERMISSION: //拨打电话
                if (permissions.length != 0 && grantResults[0] != PackageManager.PERMISSION_GRANTED) {//失败
                    Toast.makeText(this, "请允许拨号权限后再试", Toast.LENGTH_SHORT).show();
                } else {//成功
                    Toast.makeText(this, "成功", Toast.LENGTH_SHORT).show();
//                    call("tel:" + "10086");
                }
                break;
        }
    }

}
