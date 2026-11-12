# Course Generator IQ — champ Garmin Fēnix 9 Pro 51 mm

Ce sous-projet est un **champ de données Connect IQ** (pas une application autonome).
Le module est développé sur `experiment/terrain-debiased-curve-v1` sans modifier le Java/Maven de Course Generator.
**Statut : code poussé, non encore recompilé sur le SDK macOS 9.3.0 après ces ajouts.**

## Démarrer

1. Ouvrir `connect-iq/CourseGeneratorIQ` comme **dossier VS Code**.
2. `Monkey C: Select Device` → `fenix9pro51mm`.
3. `Monkey C: Run`. L'écran doit d'abord afficher **CG IQ / DEMO**.
4. Pour un vrai roadbook, remplir les trois champs Base64 dans les réglages Connect IQ.

Le fichier `resources/drawables/launcher.png` (64×64) est désormais dans le dépôt.

## Déjà implémenté

- Décodeur binaire offline **CGIQ v1**, trois blocs Base64, version et somme de contrôle.
- 1 à 25 points avec nom, distance, D+ et D− cumulés, temps planifié, pause, coupure horaire, coordonnées GPS optionnelles.
- Calcul de la **marge prévisionnelle** à la prochaine barrière (départ ou arrivée du ravito), temps de pause inclus.
- Heure cible, ETA estimée, allure moyenne de déplacement requise jusqu'à la barrière, D+ restant approximatif.
- Ajustement de l'ETA suivant l'efficacité constatée sur le segment depuis le ravito précédent, borné entre 0,65 et 2,2.
- **LAP manuel** : arrivée dans le ravito puis départ au LAP suivant. Auto Lap ignoré via `onTimerLap2`.
- Détection GPS facultative : arrivée lente et stable proche du point et départ par éloignement.
- Pause chronométrée, vibrations à l'approche de la fin de pause et au terme prévu.
- Sauvegarde de la progression et recalage kilométrique à chaque point, écran compact et plein écran.
- Départ personnel +0 à +180 min **sans décaler les barrières officielles**.

## Générer les trois blocs sur Mac

Le générateur offline (Python standard, aucune bibliothèque externe) utilise actuellement un fichier **JSON intermédiaire**.

```bash
cd ~/Desktop/Course_Generator/connect-iq/CourseGeneratorIQ
python3 tools/encode_roadbook.py tools/demo-roadbook.json
python3 tools/encode_roadbook.py tools/demo-roadbook.json -o /tmp/cgiq-blocs.txt
```

Le terminal affiche **BLOC 1**, **BLOC 2**, **BLOC 3**. Copier **seulement les chaînes Base64** dans les champs correspondants de Garmin Connect IQ / Garmin Connect, sans la mention « BLOC ».

Dans le simulateur : **File → Edit Persistent Storage → Edit Application.Properties data**, puis modifier les propriétés et relancer l'activité.

### JSON de référence

```json
{
  "name": "DEMO FICTIVE",
  "official_start": "07:30",
  "points": [
    {
      "name": "R1",
      "km": 12.35,
      "ascent_m": 330,
      "descent_m": 210,
      "target_elapsed": "02:15",
      "pause_min": 10,
      "cutoff": "10:30",
      "cutoff_after_pause": true,
      "lat": 43.85,
      "lon": 4.36
    },
    {
      "name": "FIN",
      "km": 87.90,
      "ascent_m": 2269,
      "descent_m": 2361,
      "target_elapsed": "16:53",
      "pause_min": 0,
      "cutoff": "26:45",
      "cutoff_after_pause": false
    }
  ]
}
```

**Ces points sont illustratifs, pas les données officielles de Veni Vici.**

- `official_start` : départ officiel HH:MM. Le délai personnel est réglé séparément.
- `target_elapsed` : passage planifié **en temps écoulé depuis le départ officiel**, pauses antérieures incluses.
- `cutoff` : heure locale officielle, **pas** une durée. `26:45` signifie 02:45 le lendemain. `null` signifie pas de barrière.
- `cutoff_after_pause` : `true` si la limite s'applique au départ du point, donc pause décomptée.
- `ascent_m` et `descent_m` : dénivelés **cumulés**. Les coordonnées GPS sont facultatives, mais nécessaires pour détecter le ravito automatiquement.
- `km` : distance cumulée officielle arrondie à 10 m. Noms : ASCII sur 18 caractères maximum (accents retirés).

## Paramètres du champ

| Clé | Valeur / rôle |
| --- | --- |
| Block1, Block2, Block3 | Trois parties du même Base64 |
| StartDelay | Décalage de départ 0 à 180 minutes |
| RestAlertMin | Vibration avant fin du ravito, 0 à 30 minutes |
| AutoGps | Arrivée/départ par GPS, activé par défaut |
| ManualLap | LAP manuel arrivée/départ, activé par défaut |
| GpsRadius | Rayon 50 à 300 m, valeur initiale 100 m |

## Tests

```bash
cd ~/Desktop/Course_Generator/connect-iq/CourseGeneratorIQ
python3 -m unittest discover -s tools -p 'test_*.py' -v
```

Tests à réaliser sur le simulateur / la montre :
1. Sans blocs : **CG IQ / DEMO** et premier ravito à 5 km.
2. Avec les blocs générés depuis `demo-roadbook.json` : affichage du plan réel chargé.
3. Simulation d'une activité en mouvement : diminution de la distance, ETA et marge.
4. Un LAP manuel : ravito ; un deuxième : point suivant.
5. Un Auto Lap : **ne doit pas** modifier le point courant.
6. Pause : vibrations avant et à la fin ; départ par GPS ou LAP.
7. Un décalage de départ +15 min : les BH restent fixes et la marge diminue de 15 min.
8. Une lettre Base64 modifiée : erreur de contrôle, pas de fausses données.
9. Sortie/retour sur le champ : progression conservée.

## Limites importantes

- **Pas encore de test compile/terrain** de ce lot. Les erreurs du compilateur local, s'il y en a, doivent être corrigées avant une sortie.
- La projection utilise la **distance de l'activité Garmin** recalée aux ravitos. Pas encore de projection de position sur la trace GPX.
- Le **D+ restant** est estimé linéairement dans la section ; il ne reflète pas une analyse d'altitude point par point.
- La vitesse terrain et la fatigue avancées du moteur Java **ne sont pas encore exportées** dans le protocole v1. Le correctif de vitesse en cours de segment reste approximatif, notamment en forte descente.
- La vibration doit être testée lors de la mise en veille/changement d'écran.
- **L'export direct JSON/Base64 depuis l'éditeur Java Course Generator reste à intégrer.** Pour cette tranche, l'encodeur Python fournit un protocole stable et testable, sans supposer le contenu du roadbook réel.
- Le mode démo est fictif, jamais un plan officiel. Ne pas utiliser la marge affichée comme garantie de passage.
