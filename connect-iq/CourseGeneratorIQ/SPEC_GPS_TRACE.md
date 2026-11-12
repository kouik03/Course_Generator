# Spécification prioritaire — suivi GPS sur la trace embarquée (Course Generator IQ)

> **Statut : objectif produit à implémenter.** Ce document fixe le comportement attendu pour les prochaines sessions. Il ne décrit **pas** une fonctionnalité déjà opérationnelle.
>
> Champ de données Connect IQ sur Fēnix 9 Pro / Fēnix 8 ; fonctionnement autonome, hors ligne, indépendamment du parcours de navigation éventuellement chargé sur la montre. Conserver la branche `experiment/terrain-debiased-curve-v1`, les travaux Java existants et les trois champs Base64 dans Garmin Connect IQ mobile.
>
> **Priorité absolue : la distance, le temps restant et la barre de progression doivent provenir de la position projetée sur la trace embarquée, jamais du seul kilométrage brut de l'activité Garmin.**

## 1. Source des données de parcours

- Course Generator (Mac/Java) exporte dans les blocs Base64 une **géométrie de parcours** décodable par le champ Monkey C, avec distance curviligne officielle et profil altimétrique, plus les ravitaillements/points de passage et leurs données métier.
- Examiner la compression adaptative du format **TR1** déjà disponible dans le projet et dans le fichier `routecode-adaptive.js` fourni : réutiliser les *principes* de réduction de points, protection des CP, quantification et intégrité, sans supposer d'emblée que le format actuel est optimal ni interchangeable avec CGIQ.
- Conserver pour l'instant **CGIQ v1 et son encodeur Python** : aucune migration implicite. Définir et tester un futur format compatible/versionné, l'export Java correspondant et le décodeur Monkey C avant bascule.
- Trois blocs saisissables **sur téléphone**, pas sur la montre ; aucune dépendance réseau au démarrage ou en course.
- Décider le budget binaire d'après les limites réelles des paramètres Connect IQ, la RAM/CPU et la fidélité des tracés techniques. Indiquer la précision géométrique et altimétrique résultante.

## 2. Projection GPS sur la trace (fondement de la progression)

À chaque position valide fournie par le GPS de la montre :

1. Chercher la projection plausible de la position sur la polyligne encodée (segment/abscisse curviligne `s`), calculer son **écart latéral** `d` et la fiabilité du rapprochement.
2. Préférer un appariement *continu* dans le temps : vitesse, cap éventuel, ordre des segments, traces qui se croisent, lacets, tronçons aller-retour ou parallèles proches. Une simple recherche du point géométriquement le plus proche sur tout le parcours peut produire des sauts artificiels.
3. Calculer `s` en **mètres sur le parcours officiel** ; `s` n'est **pas** `Activity.Info.elapsedDistance`, qui comprend les détours et les erreurs GPS.
4. **Écart de parcours** : si l'écart latéral ou la confiance franchit un seuil paramétrable avec hystérésis, afficher clairement `HORS PARCOURS`, éventuellement l'écart métrique et la direction de retour lorsque fiable. Ne pas avancer artificiellement `s` pendant le détour. Ne pas prétendre que la distance au ravito diminue parce que l'athlète marche/court hors de la trace.
5. **Réintégration** : dès que la position est de nouveau compatible avec la trace, reprendre à l'abscisse réellement atteinte. Supporter demi-tours/retours en arrière ; éviter de forcer une progression strictement monotone qui masquerait un vrai recul. Prévoir des protections contre les sauts GPS.
6. Conserver séparément la distance d'activité Garmin, la distance virtuelle/confirmée du roadbook et la distance **reconnue sur la trace**. Un recalage manuel du prochain CP implique la validation des CP précédents, sans falsifier le FIT ou créer une pseudo-position GPS.

### Exemple d'acceptation indispensable

Un coureur parcourt **10 km au GPS de la montre**, mais ses détours ne lui ont fait gagner que **2 km le long de la trace** :
- progression affichée : **2 km de parcours officiel**, pas 10 ;
- distance restante au prochain CP : `s_CP - 2 km` (et non `s_CP - 10 km`) ;
- barre et temps restant recalculés depuis cette progression de **2 km** ;
- hors tracé : avertissement de sortie et distance/ETA non faussement améliorées.

## 3. Ravitaillements automatiques

- Chaque CP garde son emplacement sur la trace, ses coordonnées, sa distance officielle et son type (eau, alimentation, passage, arrivée).
- **Arrivée automatique** uniquement quand la position est compatible **à la fois** avec la proximité géographique du CP **et** avec sa position sur le tracé, avec seuil de tolérance et temporisation anti-rebond ; ne pas déclencher un CP proche à vol d'oiseau mais sur une autre portion/lacet.
- **Départ automatique** après éloignement confirmé et progression sur le tronçon suivant ; mesurer la pause réelle, conserver les alertes de fin de pause.
- Bouton LAP et menu de recalage manuel comme solutions de secours ; pas de double comptage ni de passage intempestif après un recalage.
- Ne pas dépendre du guidage natif Garmin ou de la présence d'un GPX installé séparément sur la montre.

## 4. Distance et calcul du temps restant avec le D+

Le prochain CP est à l'abscisse `s_CP` et la position appariée est `s` :

- distance restante officielle : `max(0, s_CP - s)` ;
- D+ restant calculé sur le **profil altimétrique embarqué entre s et s_CP**, pas une interpolation linéaire sur un tronçon entier ;
- distance-effort équivalente de référence :

```text
D_eq (km plats) = D_trace_restante (km) + D+_restant (m) / K
K par défaut = 100 m de D+ pour 1 km plat équivalent
```

- **K doit être configurable depuis la montre ET depuis les réglages Garmin Connect IQ sur téléphone**, avec priorité de la valeur choisie sur la montre et une action de retour au réglage téléphone. Ajouter validation, bornes explicites et persistance.
- La règle `100 m D+ = 1 km plat` est **une hypothèse configurable de modélisation**, pas une loi physiologique universelle ; il faudra préciser le rôle du D− (ne pas lui appliquer automatiquement le même poids sans décision et tests).
- Le temps de progression estimé doit dépendre de `D_eq` restante et d'une vitesse d'effort mesurée **sur la trace reconnue**, ainsi que du modèle de course Course Generator (terrain/fatigue, pauses restantes, retards éventuels), sans récompenser les détours. Hors parcours, signaler l'incertitude ou suspendre les adaptations liées à l'avancement.
- Barrières horaires **officielles inchangées** par le départ en vague. Bien distinguer compteur de temps jusqu'à la BH et marge estimée après temps de mouvement/pause.

## 5. Affichage — dette de qualité à corriger

- Garder l'ergonomie inspirée de la **photo de TrailRace+ PRO fournie** : prochain CP et services en haut, grandes valeurs essentielles, barre fine bleu-gris avec CP bien contrastés et coureur blanc, allure/requis, D−/arrivée, horloge et chronomètre.
- **Éliminer tous les chevauchements et textes tronqués** sur l'écran principal **et sur toutes les pages de menu**, y compris noms de CP longs, temps à plusieurs chiffres, valeurs négatives et parcours de plus de 24 h.
- Mesurer les textes avec les polices réellement disponibles sur la Fēnix 9 Pro 51 mm, respecter les cordes utiles du cercle ; prévoir des libellés courts et une stratégie de police/colonnes **avant** de tronquer.
- Les menus : confirmation du prochain CP et « Retour au suivi » doivent fermer proprement les réglages, sans fermer le simulateur ; « Consulter un CP » doit rester sans effet sur le suivi réel.
- Garder les deux moteurs de menu testables : personnalisé par `CGIq` et natif `Menu2` par `CGIq menu2`, avec une sélection de build explicite. Valider la variante native sur montre physique avant de la considérer comme acquise.

## 6. Tests de validation à obtenir avant de considérer le produit prêt

1. Parcours simulé *sur trace* : 0 → R1 → R2 → arrivée, progression, D+ restant, temps et affichage cohérents.
2. Détour de 1 km, puis retour au même endroit : **aucune avance de 1 km sur la trace** et alerte hors parcours.
3. Course de 10 km au compteur Garmin pour 2 km de progression réelle sur trace : distance/ETA/barre = **2 km**, non 10.
4. Lacets et deux branches proches/croisement : pas de saut au CP suivant ; déclenchement seulement sur segment cohérent.
5. Recul réel puis reprise : position sur trace et distance au CP réévaluées correctement.
6. Ravitaillement : entrée automatique, temporisation, pause, départ automatique, alerte ; LAP manuel compatible sans doublons.
7. Sélection manuelle de **FIN** alors que R1 et R2 sont confirmés : barre minimale au dernier CP validé (11/18 km dans la démo), sans injecter de GPS artificiel.
8. Modifier `K` sur téléphone puis sur montre : 100 m D+ = 1 km équivalent par défaut ; ETA change sans altérer distance géométrique, CP ou barrière officielle.
9. Démarrage en vague et passage à minuit : dates/heures et marges fiables.
10. Test de rendu sur toutes les pages, longues chaînes, plusieurs tailles de champ, batterie/mémoire et longue activité.
11. Vérifier un build compilé **et exécuté** avec SDK Garmin sur Mac ; les tests Python/GitHub actuels ne valident pas Monkey C.

## 7. Ordre recommandé de réalisation

**P0 — Vérité du trajet** : format et export géométrie/profil, décodeur, appariement GPS et détection hors parcours.

**P1 — Ravitaillements et calculs** : arrivée/départ automatiques, progression du roadbook et D+ restant réel, coefficient `K` depuis téléphone + montre, ETA/marge basées sur le trajet.

**P2 — Interface et robustesse** : résoudre les tronquages/menus restants et réaliser des tests utilisateurs/simulateur sur traces sinueuses et ultras longs. Les bugs actuels les plus gênants d'affichage peuvent naturellement être corrigés avant P0 pour faciliter les tests, sans les confondre avec l'objectif central.

## 8. État honnête au moment de rédaction

Le prototype actuel sait lire **CGIQ v1**, les CP et certaines coordonnées, mais **n'embarque pas la polyligne/profil complet** et calcule encore sa progression avec `Activity.Info.elapsedDistance` et un recalage virtuel manuel. Le D+ ou D− restant est encore estimé linéairement au sein d'un tronçon. Il **ne sait pas** actuellement calculer l'avancement réel sur la trace ni identifier de façon fiable une sortie de parcours. Ces limites sont des éléments à remplacer, **pas** des fonctionnalités terminées.
