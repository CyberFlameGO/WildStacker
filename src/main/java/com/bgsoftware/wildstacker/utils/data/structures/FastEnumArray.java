package com.bgsoftware.wildstacker.utils.data.structures;

import sun.misc.SharedSecrets;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

public final class FastEnumArray<E extends Enum<E>> {

    private final Boolean[] arr;
    private final Class<E> keyType;

    private boolean containsAll = false;
    private int size = 0;

    public FastEnumArray(Class<E> keyType){
        this(SharedSecrets.getJavaLangAccess().getEnumConstantsShared(keyType).length, keyType);
    }

    FastEnumArray(int initSize, Class<E> keyType){
        arr = new Boolean[initSize];
        this.keyType = keyType;
        Arrays.fill(arr, false);
    }

    public static <E extends Enum<E>> FastEnumArray<E> fromList(List<String> arr, Class<E> keyType){
        FastEnumArray<E> fastEnumArray = new FastEnumArray<>(keyType);
        arr.forEach(line -> {
            if(line.equalsIgnoreCase("ALL")){
                fastEnumArray.containsAll = true;
            }
            else {
                E value = FastEnumUtils.getEnum(keyType, line.toUpperCase());
                if (value != null)
                    fastEnumArray.add(value);
            }
        });
        return fastEnumArray;
    }

    public boolean add(E e) {
        return add(e.ordinal());
    }

    boolean add(int index) {
        boolean containedBefore = arr[index];
        if(!containedBefore) {
            arr[index] = true;
            size++;
        }
        return containedBefore;
    }

    public boolean contains(E e){
        return contains(e.ordinal());
    }

    boolean contains(int index){
        return containsAll || arr[index];
    }

    public int size() {
        return size;
    }

    public Collection<E> collect(){
        E[] allValues = SharedSecrets.getJavaLangAccess().getEnumConstantsShared(keyType);
        return Arrays.stream(allValues).filter(e -> arr[e.ordinal()]).collect(Collectors.toSet());
    }

}
