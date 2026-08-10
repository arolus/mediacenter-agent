// Перехват брендовых кнопок пульта (Xiaomi TV+, YouTube, Netflix, Prime Video…).
//
// Эти кнопки — обычные клавиши: пульт Xiaomi шлёт их как BUTTON_1…BUTTON_16, а лаунчер Google TV,
// не найдя удалённого приложения, уводит зрителя в Play Store. Служба специальных возможностей —
// единственный способ без root получить нажатия ГЛОБАЛЬНО (в любом приложении) и съесть их:
// обычное приложение видит клавиши только когда оно на экране.
//
// Всё, что мы делаем, — открываем медиатеку. Никакого чтения содержимого экрана: в описании
// службы стоит только canRequestFilterKeyEvents.
package com.mediacenter.tv;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

public class KeyService extends AccessibilityService {
    private static final String TAG = "MCKeys";

    // BUTTON_1…BUTTON_16 (188…203) — сюда попадают все брендовые кнопки этого пульта;
    // ALL_APPS (284) — кнопка «Приложения». Обычные DPAD/OK/Back/громкость не трогаем.
    private static boolean ours(int code) {
        return (code >= KeyEvent.KEYCODE_BUTTON_1 && code <= KeyEvent.KEYCODE_BUTTON_16) || code == 284;
    }

    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        // Временная диагностика: какие коды реально доходят до службы (adb logcat -s MCKeys)
        if (e.getAction() == KeyEvent.ACTION_DOWN) Log.d(TAG, "key " + e.getKeyCode());
        if (!ours(e.getKeyCode())) return false;          // чужое — пропускаем дальше
        if (e.getAction() == KeyEvent.ACTION_DOWN) open();
        return true;                                      // и DOWN, и UP съедаем: иначе лаунчер
    }                                                     // всё равно откроет магазин по UP

    private void open() {
        try {
            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            // Кнопка означает «домой»: если приложение уже открыто на карточке фильма или
            // в трейлере, activity просто вышла бы на передний план тем же экраном.
            i.putExtra("goHome", true);
            startActivity(i);
        } catch (Exception ex) {
            Log.d(TAG, "open: " + ex.getMessage());
        }
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { /* не слушаем экран */ }
    @Override public void onInterrupt() { }
}
