// Device Admin приёмник — нужен ТОЛЬКО ради права lockNow() (гасить экран после простоя,
// см. MainActivity.idleOff). Никаких политик не навязываем; тело пустое.
package com.mediacenter.tv;

import android.app.admin.DeviceAdminReceiver;

public class AdminReceiver extends DeviceAdminReceiver {
}
