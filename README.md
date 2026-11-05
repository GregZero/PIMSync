# PIMSync — prototype SAF et moteur d’opérations

Cette application utilise le dossier local sélectionné avec SAF. Elle ne communique pas avec Syncthing-Fork, ne démarre aucun service Syncthing et ne lit ni ne modifie les contacts, agendas ou tâches du téléphone. La note proposée dans l’application est fictive.

## Compilation sur GitHub

Le workflow **Build Android prototype** se lance à chaque envoi de code et demande de fusion. Il exécute les tests du moteur, compile l’APK Android et publie l’artefact `pimsync-prototype-debug-apk` pendant 14 jours.

## Essai sur Android

1. Téléchargez l’artefact depuis une exécution réussie dans GitHub Actions, décompressez-le, puis installez l’APK.
2. Sur chaque téléphone, choisissez le même dossier local déjà synchronisé. Donnez un identifiant différent à chaque appareil.
3. Vérifiez le test de fichiers SAF. Il crée, lit et modifie un fichier temporaire, tente de le renommer, puis le supprime dans `PIMSync/prototype-tests/<identifiant>/`.
4. Entrez un texte fictif et choisissez **Enregistrer cette note de test**. Une opération JSON immuable est écrite dans `PIMSync/devices/<identifiant>/operations/`.
5. Choisissez **Actualiser l’état et les conflits** après que Syncthing-Fork a répliqué les opérations. Une seule version causale apparaît comme actuelle ; les versions concurrentes sont affichées comme conflit.

Pour vérifier un conflit, créez d’abord une note de base et attendez qu’elle soit répliquée sur les deux appareils. Déconnectez temporairement les appareils de Syncthing-Fork, enregistrez un texte différent sur chacun, puis reconnectez-les. Après réplication, actualisez l’état sur l’un des téléphones : les deux versions doivent rester visibles. Une nouvelle note enregistrée après lecture du conflit le résout explicitement avec le texte saisi.

Les actions de suppression écrivent un marqueur de suppression (tombstone) au lieu d’effacer les opérations précédentes. Une suppression et une modification faites hors ligne doivent apparaître ensemble comme conflit.

Les modifications de fichier restent dans un dossier d’essai. Les modifications de la note fictive restent dans le journal PIMSync. Aucun fournisseur Android de contacts, agenda, tâches ou notes n’est utilisé à cette étape.

## Versions de compilation

- Android Gradle Plugin 8.7.3, Gradle 8.9 et Java 17
- Kotlin 2.0.21, SDK de compilation 35, Android minimum 6.0 (API 23)
