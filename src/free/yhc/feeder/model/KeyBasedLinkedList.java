/*****************************************************************************
 *    Copyright (C) 2012 Younghyung Cho. <yhcting77@gmail.com>
 *
 *    This file is part of Feeder.
 *
 *    This program is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU Lesser General Public License as
 *    published by the Free Software Foundation either version 3 of the
 *    License, or (at your option) any later version.
 *
 *    This program is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU Lesser General Public License
 *    (<http://www.gnu.org/licenses/lgpl.html>) for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *****************************************************************************/

package free.yhc.feeder.model;

import java.util.Iterator;
import java.util.LinkedList;

public class KeyBasedLinkedList<T> {
    private LinkedList<Elem> l = new LinkedList<Elem>();

    private static class Elem {
        final Object key;
        final Object item;
        Elem(Object aKey, Object aItem) {
            key = aKey;
            item = aItem;
        }
    }

    private class Iter implements Iterator<T> {
        Iterator<Elem> itr = l.iterator();

        @Override
        public boolean
        hasNext() {
            return itr.hasNext();
        }

        @Override
        public T
        next() {
            return (T)itr.next().item;
        }

        @Override
        public void
        remove() {
            itr.remove();
        }
    }

    public KeyBasedLinkedList() {
    }

    public boolean
    add(Object key, T item) {
        return l.add(new Elem(key, item));
    }

    public void
    addFirst(Object key, T item) {
        l.addFirst(new Elem(key, item));
    }

    public void
    addLast(Object key, T item) {
        l.addLast(new Elem(key, item));
    }

    public void
    remove(Object key) {
        Iterator<Elem> itr = l.iterator();
        while (itr.hasNext()) {
            Elem e = itr.next();
            if (e.key == key)
                itr.remove();
        }
    }

    public boolean
    remove(Object key, T item) {
        Iterator<Elem> itr = l.iterator();
        while (itr.hasNext()) {
            Elem e = itr.next();
            if (e.key == key && e.item == item) {
                itr.remove();
                return true;
            }
        }
        return false;
    }

    public Iterator<T>
    iterator() {
        return new Iter();
    }

    public T[]
    toArray(T[] a) {
        // NOT TESTED enough yet!
        Elem[] es = l.toArray(new Elem[0]);
        if (a.length < es.length)
            a = (T[])Utils.newArray(a.getClass().getComponentType(), es.length);
        for (int i = 0; i < es.length; i++)
            a[i] = (T)es[i].item;
        return a;
    }
}
