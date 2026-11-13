# Feuille de route

Idées de fonctionnalités pour SimpleRadio, classées par rapport intérêt / effort. L'objectif reste une appli minimaliste : chaque ajout doit servir à l'écoute au quotidien.

## Prioritaire

### 1. Titre en cours (métadonnées ICY) — ✅ fait
Beaucoup de flux diffusent « artiste – titre » dans leurs métadonnées ICY. Aujourd'hui, la barre de lecture et la notification n'affichent que le pays, les tags et le débit de la station.
- Écouter `onMediaMetadataChanged` / lire `player.mediaMetadata` (ExoPlayer y place déjà les infos ICY) et l'afficher dans `PlayerBar`.
- Faire remonter ce titre dans la notification et sur l'écran de verrouillage.
- Effort : faible.

### 2. Reconnexion automatique après une coupure réseau
En 4G, en voiture ou au passage Wi-Fi ↔ mobile, le flux tombe : l'appli affiche « Impossible de lire ce flux » et s'arrête.
- Dans `PlaybackService`, sur erreur réseau (`ERROR_CODE_IO_*`), relancer `prepare()` quelques fois avec un délai croissant (ex. 2 s, 5 s, 10 s), puis abandonner.
- Afficher « Reconnexion… » dans la barre de lecture pendant les tentatives.
- Effort : faible à moyen.

### 3. Mémoriser la dernière station
- Enregistrer la dernière station jouée (mêmes préférences que les favoris).
- Au lancement, afficher la barre de lecture avec cette station, prête à démarrer.
- Implémenter `onPlaybackResumption` dans le callback de la session pour que le bouton lecture du casque Bluetooth ou de la voiture relance la radio, même appli fermée.
- Corrige au passage un cas existant : une station relancée depuis la notification ne peut pas être ajoutée aux favoris (`favorite == null` dans `PlayerBar`).
- Effort : moyen.

### 4. Minuteur de sommeil
- Arrêt automatique après 15, 30, 60 ou 90 min (choix rapide depuis la barre de lecture).
- Afficher le temps restant ; arrêt dans le service pour fonctionner écran éteint.
- Effort : faible.

## Utile

### 5. Réorganiser les favoris
Les favoris restent dans l'ordre d'ajout. Permettre le glisser-déposer dans l'onglet Favoris.
- Effort : moyen (pas de réordonnancement natif dans `LazyColumn`).

### 6. Filtres de recherche
Aujourd'hui, la recherche ne porte que sur le nom. L'API Radio Browser permet de filtrer par tag et par pays (`tag=`, `countrycode=`).
- Puces de genres courants (info, jazz, rock, classique…) et sélecteur de pays dans l'onglet Découvrir.
- Effort : faible à moyen.

### 7. Écoutées récemment
Liste des 10 dernières stations, pour retrouver une radio découverte sans l'avoir mise en favori.
- Effort : faible (s'appuie sur le n° 3).

### 8. Export / import des favoris
Export et import d'un fichier JSON (même format que `Station.toJson`).
- Utile pour changer de téléphone ou passer d'une version debug à une release, puisque la désinstallation efface les favoris.
- Effort : faible (Storage Access Framework).

## Plus tard

### 9. Réveil radio
Démarrer une station favorite à une heure donnée (`AlarmManager` + permission d'alarme exacte).

### 10. Tuile Réglages rapides / widget
Lancer ou couper la dernière station sans ouvrir l'appli. Dépend du n° 3.

### 11. Android Auto
Passer à `MediaLibraryService` pour exposer les favoris dans l'interface de la voiture. Pertinent seulement avec un véhicule compatible Android Auto.

## Ordre suggéré

1 → 2 → 3 → 4, puis selon l'usage. Les trois premiers améliorent la fiabilité perçue à chaque écoute ; le minuteur est rapide à livrer ensuite.
