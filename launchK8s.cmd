@echo off
echo [1/5] Selection du contexte Docker Desktop...
kubectl config use-context docker-desktop
if %errorlevel% neq 0 goto :error

echo [2/5] Build de l'image Docker locale...
docker build -t payment-gateway-lab:local .
if %errorlevel% neq 0 goto :error

echo [3/5] Import de l'image dans les 3 nodes du cluster...
docker save payment-gateway-lab:local -o payment-gateway-lab.tar
if %errorlevel% neq 0 goto :error

for %%n in (desktop-control-plane desktop-worker desktop-worker2) do (
    echo   -^> Import sur %%n...
    docker exec -i %%n ctr -n k8s.io images import - < payment-gateway-lab.tar
    if %errorlevel% neq 0 (
        del /f /q payment-gateway-lab.tar 2>nul
        goto :error
    )
)
del /f /q payment-gateway-lab.tar

echo [4/5] Deploiement dans Kubernetes...
kubectl apply -f deploy/k8s/k8s-local.yml
if %errorlevel% neq 0 goto :error

echo [5/5] Attente du demarrage des pods...
kubectl rollout status deployment/postgres --timeout=60s
if %errorlevel% neq 0 goto :error
kubectl rollout status deployment/payment-app --timeout=120s
if %errorlevel% neq 0 goto :error

echo.
echo Application deployee sur http://localhost:8080 (via Service LoadBalancer)
kubectl port-forward svc/payment-app 8080:8080
goto :end

:error
echo.
echo [ERREUR] Echec a l'etape precedente (code %errorlevel%).

:end
echo.
pause