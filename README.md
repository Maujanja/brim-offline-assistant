# Msaidizi – amri za sauti kwa simu (Android)

Wake word ya kweli inayotumia **Picovoice Porcupine**: inafanya kazi kwenye simu yenyewe,
bila intaneti, na kwa matumizi madogo ya betri. Ikisikia wake word, app inatoa mlio kisha
inasikiliza amri yako.

## Hatua za kuanza
1. Jisajili bure kwenye https://console.picovoice.ai na nakili **AccessKey** yako.
2. Fungua folda hii kwa **Android Studio**, subiri Gradle sync, kisha **Run** kwenye simu.
3. Ndani ya app bandika AccessKey, chagua neno la kuamsha (mfano JARVIS, COMPUTER, PORCUPINE),
   bonyeza **Anza kusikiliza** na ruhusu maikrofoni na arifa.
4. Bonyeza **Ruhusu kufungua kamera/apps** na uwashe "Display over other apps".
5. Sema: "Jarvis" → mlio → "washa tochi".

## Neno lako maalum (mfano "Hey Msaidizi")
1. Kwenye Picovoice Console > Porcupine, andika kifungu cha maneno, chagua lugha, bonyeza Train.
2. Pakua faili la `.ppn` la **Android**, lihamishe kwenye simu.
3. Ndani ya app bonyeza **Chagua faili la .ppn** na ulichague. Bonyeza **Anza kusikiliza** tena.

Kumbuka: Porcupine haina modeli rasmi ya Kiswahili (lugha zinazotumika: Kiingereza, Kiarabu,
Kichina, Kifaransa, Kijerumani, Kihindi, Kiitaliano, Kijapani, Kikorea, Kireno, Kirusi,
Kihispania na nyinginezo, bila Kiswahili). Neno la Kiswahili lililofunzwa kwa modeli ya
Kiingereza linaweza kufanya kazi vizuri au vibaya kulingana na matamshi, kwa hiyo lijaribu
na utumie "Sensitivity" (iko ndani ya `VoiceService.kt`, thamani 0.7) kurekebisha.
Maneno mafupi yenye silabi 3 hadi 4 kwa kawaida hufanya vizuri zaidi.

## Amri
| Sema (baada ya mlio) | Kinachotokea |
|---|---|
| washa / zima tochi | Tochi inawaka / inazima |
| fungua kamera (au "video") | Inafungua kamera |
| volume juu / chini / kimya | Inabadilisha sauti |
| fungua wifi / bluetooth | Inafungua paneli/mipangilio |
| saa ngapi | Inasema muda |
| piga 0712345678 | Inafungua dialer na namba |
| fungua WhatsApp (app yoyote) | Inafungua app kwa jina |

Kuongeza amri: ongeza tawi ndani ya `execute()` kwenye `CommandHandler.kt`.

## Jinsi inavyofanya kazi
`Porcupine` (kila wakati, offline) → wake word → mlio → `SpeechRecognizer` ya simu inasikiliza
amri moja (inahitaji intaneti kwenye simu nyingi) → `CommandHandler` → Porcupine inaanza tena.

## Mambo ya kujua
- AccessKey ya bure ni ya matumizi ya kibinafsi/majaribio; angalia masharti ya Picovoice
  kabla ya kutoa app kwa umma.
- Android 10+ hairuhusu kuwasha/kuzima Wi-Fi au Bluetooth moja kwa moja, kwa hiyo app
  inafungua paneli ya mipangilio.
- Simu kama Tecno, Infinix, Xiaomi, Samsung huua huduma za nyuma: zima "Battery optimization"
  kwa app hii.
- Dependency imewekwa `porcupine-android:3.0.+`. Ukitaka toleo jipya kabisa, badilisha kwenye
  `app/build.gradle.kts` (API ya `PorcupineManager.Builder` inaweza kubadilika kidogo).
