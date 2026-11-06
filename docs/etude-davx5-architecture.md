# Étude DAVx⁵ et choix d’architecture PIMSync

**État de l’étude : 6 novembre 2026**

## Ce que fait DAVx⁵

DAVx⁵ est une application Android CardDAV/CalDAV. Elle comprend une interface pour gérer les comptes et des adaptateurs qui relient les serveurs CardDAV/CalDAV aux fournisseurs Contacts et Agenda d’Android. Les fiches de contacts et les événements sont enregistrés par ces fournisseurs Android, pas dans DAVx⁵. DAVx⁵ n’est lui-même ni une application de contacts, d’agenda ou de tâches, ni un moteur pair-à-pair pour journaux de fichiers.

Le code source ouvert est dans [bitfireAT/davx5-ose](https://github.com/bitfireAT/davx5-ose), sous licence GPLv3. Le dépôt est conséquent et structuré en plusieurs modules : `app-ose`, `core` et `synctools`, avec une chaîne Android Kotlin moderne. Il référence aussi des bibliothèques séparées, notamment [dav4jvm](https://github.com/bitfireAT/dav4jvm) et [cert4android](https://github.com/bitfireAT/cert4android), qui ont leurs propres licences.

Le module [synctools](https://github.com/bitfireAT/synctools) contient des outils de synchronisation et d’accès aux données Android, mais son dépôt a été archivé le 26 mai 2026. Il est sous GPL-3.0. Son état d’archivage et ses dépendances rendent une réutilisation directe moins intéressante qu’une frontière d’intégration bien définie. Le dépôt DAVx⁵ demande aussi que les changements de fonctionnalités soient discutés et approuvés en amont ; une contribution externe ne donne donc pas le contrôle du calendrier ni de l’orientation du projet.

## Comparaison

| Option | Avantages | Coûts et risques pour PIMSync |
|---|---|---|
| Fork complet de DAVx⁵ | Bénéficie de ses écrans de comptes, de ses adaptateurs Android et de son expérience CardDAV/CalDAV. | PIMSync devrait adapter une application conçue autour des comptes serveur et des fournisseurs Android à un journal pair-à-pair SAF. Cela ajoute une base de code et des mises à jour importantes à maintenir. Le fork est soumis à GPLv3 et doit rester clairement distinct du produit amont. |
| Application totalement indépendante | Interface et architecture adaptées à PIMSync ; le moteur à journaux append-only reste sous contrôle du projet. Pas de dépendance fonctionnelle envers DAVx⁵ ni envers un serveur central. | PIMSync doit développer et maintenir ses propres adaptateurs vers les contacts et calendriers Android. Les fournisseurs et leurs comportements diffèrent selon les fabricants. Les tâches et les notes ne disposent pas d’un fournisseur universel. |
| Architecture hybride | Conserve une application PIMSync et un moteur indépendant, tout en isolant les formats et l’accès Android dans de petits adaptateurs remplaçables. Permet une réutilisation sélective de composants uniquement après vérification de leur licence, état de maintenance et compatibilité. | Il faut définir des interfaces nettes entre le moteur, les formats vCard/iCalendar et les fournisseurs Android. Aucune réutilisation de code DAVx⁵ n’est automatique ; chaque bibliothèque demanderait une vérification de licence et de maintenance. |

## Recommandation

Je recommande l’architecture hybride : garder PIMSync comme application autonome et maintenir le moteur de conflits comme module indépendant. Les premiers adaptateurs de données seront séparés du moteur et communiqueront avec les fournisseurs Android uniquement après validation explicite de l’utilisateur.

Cette voie respecte le dossier Syncthing-Fork déjà synchronisé : PIMSync continue à lire et écrire des fichiers via SAF, sans embarquer Syncthing, sans modifier sa configuration et sans serveur central. DAVx⁵ sert de référence pour l’intégration CardDAV/CalDAV et le traitement des données Android ; il n’est pas le socle de PIMSync.

Je ne recommande pas un fork DAVx⁵ à ce stade : la synchronisation de PIMSync repose sur des opérations immuables, des horloges vectorielles et la conservation des conflits. La couche DAVx⁵ vise plutôt à synchroniser des collections serveur avec les fournisseurs locaux Android. Les deux responsabilités ne coïncident pas.

## Suite proposée

1. **Terminé** : correctif de mise en page Android, avec parcours de tests ciblés validés sur les téléphones.
2. **Validé sur deux appareils** : export vCard confirmé vers le journal PIMSync et lecture du contact répliqué. Le prototype permet maintenant de consulter les nom, téléphones et e-mails du journal ; aucune fiche Android n’est modifiée.
3. **Implémenté, à valider sur les téléphones** : compte Android PIMSync, synchroniseur bidirectionnel limité aux fiches du compte PIMSync et aux objets `pimsync-test-*`, et création d’une fiche fictive. Vérifier que Contacts propose PIMSync comme destination et que les créations, modifications et suppressions de test se répliquent entre appareils.
4. Étudier ensuite les événements iCalendar et le cas particulier des tâches, qui dépend du fournisseur installé.

L’accès en écriture du synchroniseur est limité aux raw contacts rattachés au compte de type PIMSync. Il demande les autorisations Android de lecture et d’écriture avant l’activation. Les opérations de test utilisent des identifiants `pimsync-test-*` et des données fictives ; l’application n’importe pas les contacts des autres comptes.

## Sources

- [DAVx⁵ — intégration avec Android](https://www.davx5.com/faq/system-integration)
- [Code source et licence DAVx⁵ OSE](https://github.com/bitfireAT/davx5-ose)
- [Consignes de contribution DAVx⁵](https://github.com/bitfireAT/davx5-ose/blob/main/CONTRIBUTING.md)
- [Dépôt synctools et état archivé](https://github.com/bitfireAT/synctools)
- [dav4jvm](https://github.com/bitfireAT/dav4jvm)
- [cert4android](https://github.com/bitfireAT/cert4android)
