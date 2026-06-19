// Инициализация Firebase client SDK в Node + вход под аккаунтом владельца.
const { initializeApp } = require("firebase/app");
const { getAuth, signInWithEmailAndPassword, connectAuthEmulator } = require("firebase/auth");
const { getFirestore, connectFirestoreEmulator } = require("firebase/firestore");

async function initFirebase(config) {
  const app = initializeApp(config.firebase);
  const auth = getAuth(app);
  const db = getFirestore(app);

  // Локальный запуск через Firebase Local Emulator Suite.
  if (config.emulator) {
    connectAuthEmulator(auth, config.emulator.auth, { disableWarnings: true });
    connectFirestoreEmulator(db, config.emulator.firestore.host, config.emulator.firestore.port);
    console.log("⚙️ Агент подключён к Firebase эмулятору");
  }

  const cred = await signInWithEmailAndPassword(auth, config.auth.email, config.auth.password);
  console.log("✓ Firebase: вошёл как", cred.user.email);
  return { app, auth, db, uid: cred.user.uid };
}

module.exports = { initFirebase };
