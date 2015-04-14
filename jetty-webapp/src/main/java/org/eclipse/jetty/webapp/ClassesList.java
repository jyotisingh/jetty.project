//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//


package org.eclipse.jetty.webapp;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.ListIterator;
import java.util.StringTokenizer;

/* ------------------------------------------------------------ */
/**
 * Classpath classes list performs sequential pattern matching of a class name 
 * against an internal array of classpath pattern entries.
 * 
 * When an entry starts with '-' (minus), reverse matching is performed.
 * When an entry ends with '.' (period), prefix matching is performed.
 * 
 * When class is initialized from a classpath pattern string, entries 
 * in this string should be separated by ':' (semicolon) or ',' (comma).
 */

public class ClassesList extends AbstractList<String>
{
    private static class Entry
    {
        public final String _pattern;
        public final String _name;
        public final boolean _inclusive;
        public final boolean _package;     
        
        Entry(String pattern)
        {
            _pattern=pattern;
            _inclusive = !pattern.startsWith("-");
            _package = pattern.endsWith(".");
            _name = _inclusive ? pattern : pattern.substring(1).trim();
        }
        
        @Override
        public String toString()
        {
            return _pattern;
        }
    }
    
    final private List<Entry> _entries = new ArrayList<Entry>();
    
    /* ------------------------------------------------------------ */
    public ClassesList()
    {
    }
    
    /* ------------------------------------------------------------ */
    public ClassesList(String[] patterns)
    {
        setAll(patterns);
    }
    
    /* ------------------------------------------------------------ */
    public ClassesList(String pattern)
    {
        setPatterns(pattern);
    }
    
    /* ------------------------------------------------------------ */
    @Override
    public String get(int index)
    {
        return _entries.get(index)._pattern;
    }

    /* ------------------------------------------------------------ */
    @Override
    public String set(int index, String element)
    {
        Entry e = _entries.set(index,new Entry(element));
        return e==null?null:e._pattern;
    }

    /* ------------------------------------------------------------ */
    @Override
    public void add(int index, String element)
    {
        _entries.add(index,new Entry(element));
    }

    /* ------------------------------------------------------------ */
    @Override
    public String remove(int index)
    {
        Entry e = _entries.remove(index);
        return e==null?null:e._pattern;
    }
    
    /* ------------------------------------------------------------ */
    public boolean remove(String pattern)
    {
        for (int i=_entries.size();i-->0;)
        {
            if (pattern.equals(_entries.get(i)._pattern))
            {
                _entries.remove(i);
                return true;
            }
        }
        return false;
    }

    /* ------------------------------------------------------------ */
    @Override
    public int size()
    {
        return _entries.size();
    }

    /* ------------------------------------------------------------ */
    /**
     * Initialize the matcher by parsing each classpath pattern in an array
     * 
     * @param classes array of classpath patterns
     */
    private void setAll(String[] classes)
    {
        _entries.clear();
        addAll(classes);
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @param classes array of classpath patterns
     */
    private void addAll(String[] classes)
    {
        if (classes!=null)
            addAll(Arrays.asList(classes));
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @param classes array of classpath patterns
     */
    public void prepend(String[] classes)
    {
        if (classes != null)
        {
            int i=0;
            for (String c : classes)
            {
                add(i,c);
                i++;
            }
        }
    }
    
    
    /* ------------------------------------------------------------ */
    /**
     * Initialize the matcher by parsing a classpath pattern string
     * 
     * @param classes classpath string which may be colon or comma separated
     */
    public void setPatterns(String classes)
    {
        _entries.clear();
        addPatterns(classes);
    }

    /* ------------------------------------------------------------ */
    /**
     * Parse a classpath pattern string and appending the result
     * to the existing configuration.
     * 
     * @param patterns classpath string which may be colon or comma separated
     */
    public void addPatterns(String patterns)
    {
        StringTokenizer entries = new StringTokenizer(patterns, ":,");
        while (entries.hasMoreTokens())
            add(entries.nextToken());
    }   
    

    /* ------------------------------------------------------------ */
    /**
     * @param classes classpath string which may be colon or comma separated
     */
    public void prependPatterns(String classes)
    {
        StringTokenizer entries = new StringTokenizer(classes, ":,");
        int i=0;
        while (entries.hasMoreTokens())
        {
            add(i,entries.nextToken());
            i++;
        }
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @return array of classpath patterns
     */
    public String[] getPatterns()
    {
        String[] patterns = new String[_entries.size()];
        for (int i=0;i<_entries.size();i++)
            patterns[i]=_entries.get(i)._pattern;
        
        return patterns;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return List of classes excluded class exclusions and package patterns
     */
    public List<String> getClasses()
    {
        List<String> list = new ArrayList<>();
        for (Entry e:_entries)
        {
            if (e._inclusive && !e._package)
                list.add(e._name);
        }
        return list;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Match the class name against the pattern
     *
     * @param name name of the class to match
     * @return true if class matches the pattern
     */
    public boolean match(String name)
    {       
        boolean result=false;

        if (_entries != null)
        {
            name = name.replace('/','.');

            int startIndex = 0;

            while(startIndex < name.length() && name.charAt(startIndex) == '.') {
                startIndex++;
            }

            int dollar = name.indexOf("$");

            int endIndex =  dollar != -1 ? dollar : name.length();

            for (Entry entry : _entries)
            {
                if (entry != null)
                {               
                    if (entry._package)
                    {
                        if (name.regionMatches(startIndex, entry._name, 0, entry._name.length()))
                        {
                            result = entry._inclusive;
                            break;
                        }
                    }
                    else
                    {
                        int regionLength = endIndex-startIndex;
                        if (regionLength == entry._name.length()
                                && name.regionMatches(startIndex, entry._name, 0, regionLength))
                        {
                            result = entry._inclusive;
                            break;
                        }
                    }
                }
            }
        }
        return result;
    }

    public void addAfter(String afterPattern,String... patterns)
    {
        if (patterns!=null && afterPattern!=null)
        {
            ListIterator<String> iter = listIterator();
            while (iter.hasNext())
            {
                String cc=iter.next();
                if (afterPattern.equals(cc))
                {
                    for (int i=0;i<patterns.length;i++)
                        iter.add(patterns[i]);
                    return;
                }
            }
        }
        throw new IllegalArgumentException("after '"+afterPattern+"' not found in "+this);
    }

    public void addBefore(String beforePattern,String... patterns)
    {
        if (patterns!=null && beforePattern!=null)
        {
            ListIterator<String> iter = listIterator();
            while (iter.hasNext())
            {
                String cc=iter.next();
                if (beforePattern.equals(cc))
                {
                    iter.previous();
                    for (int i=0;i<patterns.length;i++)
                        iter.add(patterns[i]);
                    return;
                }
            }
        }
        throw new IllegalArgumentException("before '"+beforePattern+"' not found in "+this);
    }
    
}
