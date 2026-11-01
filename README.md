# PIMSync — prototype Android SAF

Ce prototype vérifie l’accès au dossier déjà synchronisé par Syncthing-Fork. Il ne communique pas avec Syncthing, ne démarre aucun service Syncthing et ne touche pas aux contacts ou agendas.

## Compiler avec GitHub

Après avoir placé ce dépôt sur GitHub, l’action **Build Android prototype** se lance à chaque envoi de code, demande de fusion, ou manuellement depuis l’onglet **Actions**. Le fichier `app-debug.apk` apparaît dans les artefacts de l’exécution pendant 14 jours.

## Essai sur Android

1. Téléchargez `pimsync-prototype-debug-apk` depuis une exécution réussie dans GitHub Actions, puis installez l’APK sur le téléphone.
2. Choisissez le dossier PIMSync existant à l’aide du sélecteur Android. L’application demande et conserve l’autorisation SAF.
3. Lancez le test des fichiers. Il crée un fichier temporaire, le lit, le modifie, tente de le renommer et le supprime dans `PIMSync/prototype-tests/<identifiant>/`.
4. Écrivez une opération. Elle est enregistrée dans un nouveau fichier JSON sous `PIMSync/devices/<identifiant>/operations/`.
5. Sur un second appareil, choisissez le même dossier et utilisez un identifiant distinct pour écrire dans un journal indépendant.

Les résultats du renommage et de la suppression dépendent du fournisseur de fichiers Android et sont affichés dans l’application.

## Versions de compilation

- Android Gradle Plugin 8.7.3, Gradle 8.9 et Java 17
- Kotlin 2.0.21, SDK de compilation 35, Android minimum 6.0 (API 23)
