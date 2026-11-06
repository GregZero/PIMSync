# PIMSync — prototype SAF et moteur d’opérations

Cette application utilise le dossier local sélectionné avec SAF. Elle ne communique pas avec Syncthing-Fork, ne démarre aucun service Syncthing et ne lit ni ne modifie les contacts, agendas ou tâches du téléphone. La note proposée dans l’application est fictive.

## Compilation sur GitHub

Le workflow **Build Android prototype** se lance à chaque envoi de code et demande de fusion. Il exécute les tests du moteur, compile l’APK Android et publie l’artefact `pimsync-prototype-<numéro>` pendant 14 jours. Le numéro de compilation GitHub apparaît dans le nom de l’artefact et dans la version Android (par exemple `0.1.42`), ce qui permet d’identifier l’APK installée.

L’étude DAVx⁵ et le choix d’architecture proposé sont documentés dans [docs/etude-davx5-architecture.md](docs/etude-davx5-architecture.md).

Le prototype comprend aussi un contrôle facultatif du fournisseur Contacts Android : après autorisation explicite, il compte les contacts et affiche uniquement ce nombre. Il ne lit pas les fiches, ne les copie pas dans le dossier PIMSync et ne modifie aucun contact.

## Essai sur Android

1. Téléchargez l’artefact depuis une exécution réussie dans GitHub Actions, décompressez-le, puis installez l’APK.
2. Sur chaque téléphone, choisissez le même dossier local déjà synchronisé. Donnez un identifiant différent à chaque appareil.
3. Vérifiez le test de fichiers SAF. Il crée, lit et modifie un fichier temporaire, tente de le renommer, puis le supprime dans `PIMSync/prototype-tests/<identifiant>/`.
4. Suivez dans l’application le **Parcours guidé — essai sur deux téléphones**. Il explique la préparation, la création et la lecture d’une note, les modifications hors ligne concurrentes, la résolution et le conflit suppression/modification.
5. Créez un code de session sur le premier téléphone et recopiez-le sur le second. Chaque session utilise un identifiant de note distinct ; aucun ancien journal n’est effacé.
6. À la fin, touchez **Copier mon compte-rendu à coller dans le chat** sur chaque téléphone et collez les deux rapports dans la conversation. L’avancement et les résultats notés sont conservés localement sur chaque téléphone.

Les opérations de chaque session restent des fichiers JSON immuables dans `PIMSync/devices/<identifiant>/operations/`. Un nouveau code de session isole l’essai sans supprimer ces fichiers ni les anciens marqueurs de suppression.

Pour vérifier un conflit, créez d’abord une note de base et attendez qu’elle soit répliquée sur les deux appareils. Déconnectez temporairement les appareils de Syncthing-Fork, enregistrez un texte différent sur chacun, puis reconnectez-les. Après réplication, actualisez l’état sur l’un des téléphones : les deux versions doivent rester visibles. Une nouvelle note enregistrée après lecture du conflit le résout explicitement avec le texte saisi.

Les actions de suppression écrivent un marqueur de suppression (tombstone) au lieu d’effacer les opérations précédentes. Une suppression et une modification faites hors ligne doivent apparaître ensemble comme conflit.

Les modifications de fichier restent dans un dossier d’essai. Les modifications de la note fictive restent dans le journal PIMSync. Aucun fournisseur Android de contacts, agenda, tâches ou notes n’est utilisé à cette étape.

## Versions de compilation

- Android Gradle Plugin 8.7.3, Gradle 8.9 et Java 17
- Kotlin 2.0.21, SDK de compilation 35, Android minimum 6.0 (API 23)
