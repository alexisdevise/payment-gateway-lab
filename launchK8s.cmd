@echo off
if not exist .env (
    echo [ERREUR] Fichier .env introuvable. Cree-le a partir du modele :  copy .env.example .env
    goto :end
)

echo [1/6] Selection du contexte Docker Desktop...
kubectl config use-context docker-desktop
if %errorlevel% neq 0 goto :error

echo [2/6] Build de l'image Docker locale...
docker build -t payment-gateway-lab:local .
if %errorlevel% neq 0 goto :error

echo [3/6] Import de l'image dans tous les nodes du cluster...
docker save payment-gateway-lab:local -o payment-gateway-lab.tar
if %errorlevel% neq 0 goto :error

rem Liste des nodes lue dans le cluster (1, 3 ou 5 nodes) : en mode kind, nom du node = nom du conteneur Docker.
rem Le ^= est obligatoire : dans un for /f, cmd prend "=" pour un separateur s'il n'est pas echappe.
for /f "delims=" %%n in ('kubectl get nodes --no-headers -o custom-columns^=:metadata.name') do (
    echo   -^> Import sur %%n...
    docker exec -i %%n ctr -n k8s.io images import - < payment-gateway-lab.tar
    if errorlevel 1 (
        del /f /q payment-gateway-lab.tar 2>nul
        goto :error
    )
)
del /f /q payment-gateway-lab.tar

echo [4/6] Creation / mise a jour du Secret depuis .env...
rem --dry-run=client -o yaml ^| apply : cree le Secret s'il n'existe pas, le met a jour sinon (rejouable).
kubectl create secret generic payment-secrets --from-env-file=.env --dry-run=client -o yaml | kubectl apply -f -
if %errorlevel% neq 0 goto :error

echo [5/6] Deploiement dans Kubernetes...
kubectl apply -f deploy/k8s/k8s-local.yml
if %errorlevel% neq 0 goto :error
rem Restart de l'app : nouvelle image + Secret relu (un pod ne relit ses variables qu'au demarrage).
rem Postgres n'est pas redemarre : inutile, ses donnees sont sur le PVC postgres-data.
rem Attention : POSTGRES_USER / POSTGRES_PASSWORD ne sont lus qu'a l'initialisation d'un volume VIDE.
rem Si tu les changes dans .env, il faut repartir d'un volume neuf (efface toutes les donnees) :
rem   kubectl delete deployment/postgres pvc/postgres-data  puis relancer ce script.
kubectl rollout restart deployment/payment-app
if %errorlevel% neq 0 goto :error

echo [6/6] Attente du demarrage des pods...
rem 180 s : la startupProbe de Postgres tolere 150 s (initialisation d'un volume vide).
kubectl rollout status deployment/postgres --timeout=180s
if %errorlevel% neq 0 goto :error
kubectl rollout status deployment/payment-app --timeout=120s
if %errorlevel% neq 0 goto :error

echo.
rem Service de type LoadBalancer : Docker Desktop publie le port sur la machine, plus besoin de port-forward.
echo Application deployee sur http://localhost:8080
kubectl get svc payment-app
goto :end

:error
echo.
echo [ERREUR] Echec a l'etape precedente (code %errorlevel%).

:end
echo.
pause
