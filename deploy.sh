gradle shadowjar

echo "Copying to cluster"
rsync -azvhuP -r --delete --exclude=".*" deploy/ cluster:/home/pfouto/edge/exps
#echo "Copying to grid"
#rsync -azvhuP -r --delete --exclude=".*" deploy/ nancy.g5k:/home/pfouto/edge/exps
echo "Done"

#gradle clean