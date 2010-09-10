#!/bin/bash
# $Id: installrtng.sh 11251 2010-07-23 23:40:44Z abhishek $ $HeadURL: svn://svn.lab.vmops.com/repos/vmdev/java/scripts/storage/secondary/installrtng.sh $
usage() {
  printf "Usage: %s: -m <secondary storage mount point> -f <system vm template file> [-F]\n" $(basename $0) >&2
  printf "or\n" >&2
  printf "%s: -m <secondary storage mount point> -u <http url for system vm template> [-F]\n" $(basename $0) >&2
}

mflag=
fflag=
while getopts 'm:f:u:F' OPTION
do
  case $OPTION in
  m)	mflag=1
		mntpoint="$OPTARG"
		;;
  f)	fflag=1
		tmpltimg="$OPTARG"
		;;
  u)	uflag=1
		url="$OPTARG"
		;;
  F)	Fflag=1 ;;
  ?)	usage
		exit 2
		;;
  esac
done

if [[ "$mflag$fflag" != "11"  && "$mflag$uflag" != "11" ]]
then
  usage
  exit 2
fi

if [[ "$fflag" == "1" && ! -f $tmpltimg ]] 
then
  echo "template image file $tmpltimg doesn't exist"
  exit 3
fi

localfile=$(uuidgen).vhd

if [ ! -d $mntpoint ] 
then
  echo "mount point $mntpoint doesn't exist\n"
  exit 4
fi

mntpoint=`echo "$mntpoint" | sed 's|/*$||'`

destdir=$mntpoint/template/tmpl/1/1/

mkdir -p $destdir
if [ $? -ne 0 ]
then
  printf "Failed to write to mount point $mntpoint -- is it mounted?\n"
fi

if [ "$Fflag" == "1" ]
then
  rm -rf $destdir/*
  if [ $? -ne 0 ]
  then
    echo "Failed to clean up template directory $destdir -- check permissions?"
    exit 2
  fi
fi

if [ -f $destdir/template.properties ]
then
  echo "Data already exists at destination $destdir -- use -f to force cleanup of old template"
  exit 4
fi

destvhdfiles=$(find $destdir -name \*.vhd)
if [ "$destvhdfiles" != "" ]
then
  echo "Data already exists at destination $destdir -- use -F to force cleanup of old template"
  exit 5
fi

tmpfile=$(dirname $0)/$localfile

touch $tmpfile
if [ $? -ne 0 ]
then
  printf "Failed to create temporary file in directory $(dirname $0) -- is it read-only or full?\n"
fi

if [ "$uflag" == "1" ]
then
  wget -O $tmpfile $url
  if [ $? -ne 0 ]
  then
    echo "Failed to fetch system vm template from $url"
    exit 5
  fi
fi


if [ "$fflag" == "1" ]
then
  cp $tmpltimg $tmpfile
  if [ $? -ne 0 ]
  then
    printf "Failed to create temporary file in directory $(dirname $0) -- is it read-only or full?\n"
  fi
fi


$(dirname $0)/createtmplt.sh -s 2 -d 'SystemVM Template' -n $localfile -t $destdir/ -f $tmpfile -u  &> /dev/null

if [ $? -ne 0 ]
then
  echo "Failed to install system vm template $tmpltimg to $destdir"
fi

tmpltfile=$destdir/$localfile
tmpltsize=$(ls -l $tmpltfile| awk -F" " '{print $5}')

echo "vhd=true" >> $destdir/template.properties
echo "id=1" >> $destdir/template.properties
echo "public=true" >> $destdir/template.properties
echo "vhd.filename=$localfile" >> $destdir/template.properties
echo "uniquename=routing" >> $destdir/template.properties
echo "vhd.virtualsize=$tmpltsize" >> $destdir/template.properties
echo "virtualsize=$tmpltsize" >> $destdir/template.properties
echo "vhd.size=$tmpltsize" >> $destdir/template.properties

echo "Successfully installed system VM template $tmpltimg to $destdir"
