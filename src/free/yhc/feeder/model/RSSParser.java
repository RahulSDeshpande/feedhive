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

import static free.yhc.feeder.model.Utils.eAssert;
import static free.yhc.feeder.model.Utils.logI;

import java.util.LinkedList;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

import android.text.Html;

public class RSSParser implements
UnexpectedExceptionHandler.TrackedModule {
    // parsing priority of namespace supported (larger number has priority)
    private static final int PRI_ITUNES     = 2;
    private static final int PRI_DEFAULT    = 1; // RSS default
    private static final int PRI_DC         = 0;

    // Result data format from parse.
    class Result {
        Feed.Channel.ParD channel = new Feed.Channel.ParD();
        Feed.Item.ParD[]  items   = null;
    }

    private class NodeValue {
        int    priority; // priority value of parsing modules which updates this value.
        String value;

        NodeValue() {
            init();
        }

        void
        init() {
            priority = -1;
            value    = "";
        }
    }

    private class ChannelValues {
        NodeValue   title       = new NodeValue();
        NodeValue   description = new NodeValue();
        NodeValue   imageref    = new NodeValue();

        void
        init() {
            title.init();
            description.init();
            imageref.init();
        }

        void
        set(Feed.Channel.ParD ch) {
            ch.title = title.value;
            ch.description = description.value;
            ch.imageref = imageref.value;
        }
    }

    private class ItemValues {
        NodeValue   title           = new NodeValue();
        NodeValue   description     = new NodeValue();
        NodeValue   link            = new NodeValue();
        NodeValue   enclosure_length= new NodeValue();
        NodeValue   enclosure_url   = new NodeValue();
        NodeValue   enclosure_type  = new NodeValue();
        NodeValue   pubDate         = new NodeValue();

        void
        init() {
            title.init();
            description.init();
            link.init();
            enclosure_length.init();
            enclosure_url.init();
            enclosure_type.init();
            pubDate.init();
        }

        void
        set(Feed.Item.ParD item) {
            item.title = title.value;
            item.description = description.value;
            item.link = link.value;
            if (null != enclosure_length.value
                || null != enclosure_url.value
                || null != enclosure_type.value) {
                item.enclosureLength = enclosure_length.value;
                item.enclosureUrl = enclosure_url.value;
                item.enclosureType = enclosure_type.value;
            }
            item.pubDate = pubDate.value;
        }
    }

    private class RSSAttr {
        String              ver = "2.0"; // by default
        LinkedList<String>  nsl = new LinkedList<String>(); // name space list.
    }

    private void
    printNexts(Node n) {
        String msg = "";
        while (null != n) {
            msg = msg + " / " + n.getNodeName();
            n = n.getNextSibling();
        }
        logI(msg + "\n");
    }

    private void
    verifyFormat(boolean cond)
            throws FeederException {
        if (!cond)
            throw new FeederException(Err.ParserUnsupportedFormat);
    }

    private Node
    findNodeByNameFromSiblings(Node n, String name) {
        while (null != n) {
            if (n.getNodeName().equalsIgnoreCase(name))
                return n;
            n = n.getNextSibling();
        }
        return null;
    }

    private String
    getTextValue(Node n)
            throws FeederException {

        if (Thread.interrupted())
            throw new FeederException(Err.Interrupted);

        String text = "";
        Node t = findNodeByNameFromSiblings(n.getFirstChild(), "#text");

        //
        // [ Issue of CDATA section. ]
        // CDATA section should be ignored at XML parser.
        // But, lots of rss feeds supported by Korean presses, uses CDATA section as a kind of text section.
        //
        // For example
        //     <title><![CDATA[[사설]지상파·케이블의 밥그릇 싸움과 방통위의 무능]]></title>
        //
        // At some cases, 'desciption' elements includs several 'cdata-section'
        // For example
        //     <description><![CDATA[[ xxx ]]> <![CDATA[[xxxx]]></description>
        //
        // In this case, there is no title string!!!
        // To support this case, we uses 'cdata' section if there is no valid 'text' section.
        // In case of there is several 'cdata-section', merge all into one string.
        // (This is kind of parsing policy!!)
        //
        // TODO
        //   This new Parsing Policy is best way to support this?
        //   Is there any elegant code structure to support various parsing policy?
        //
        if (null == t) {
            StringBuilder sbuilder = new StringBuilder();
            n = n.getFirstChild();
            while (null != n) {
                if (n.getNodeName().equalsIgnoreCase("#cdata-section"))
                    sbuilder.append(n.getNodeValue());
                n = n.getNextSibling();
            }
            text = sbuilder.toString();
        } else
            text = t.getNodeValue();

        // Lots of RSS serviced in South Korea uses raw HTML string in
        //   'title' 'description' or 'cdata-section'
        // So, we need to beautify this string.(remove ugly tags and entities)
        // This may time-consuming job.
        text = Html.fromHtml(text).toString();

        //
        // [ remove leading and trailing new line. ]
        //
        // + 'xxx' is stored.
        //     <tag>xxx</tag>
        //
        // + '\nxxx\n' is stored.
        //     <tag>
        //     xxx
        //     </tag>
        //
        // [ removing wrapping white space between '￼' character ] ???
        // Usually, image is wrapped by several white spaces.
        // This beautifies web pages, but ugly in short description.
        // Open discussion for this...
        text = Utils.removeLeadingTrailingWhiteSpace(text);

        //
        // NOTE
        //   Why "" is returned instead of null?
        //   Calling this function means interesting node is found.
        //   But, in case node has empty text, DOM parser doesn't havs '#text' node.
        //
        //   For example
        //      <title></title>
        //
        //   In this case node 'title' doesn't have '#text'node as it's child.
        //   So, t becomes null.
        //   But, having empty string as an text value of node 'title', is
        //     more reasonable than null as it's value.
        //
        return text;
    }

    // ===========================================================
    //
    //                    Name space parsor
    //
    // ===========================================================
    private class NSParser {
        private int     priority;

        private NSParser(){} // block default constructor.

        NSParser(int priority) {
            this.priority = priority;
        }

        /*
         * return: true(value is set) false(value is not set)
         */
        protected boolean
        setValue(NodeValue nv, String value) {
            if (nv.priority <= priority) {
                nv.value = value;
                return true;
            }
            return false;
        }

        /*
         * return: true(handled) false(passed)
         */
        boolean
        parseChannel(ChannelValues cv, Node n)
                throws FeederException {
            return false;
        }

        boolean
        parseItem(ItemValues iv, Node n)
                throws FeederException {
            return false;
        }
    }

    // ========================================
    //        To Support 'itunes' Namespace
    // ========================================
    private class NSItunesParser extends NSParser {
        NSItunesParser() {
            super(PRI_ITUNES);
        }

        @Override
        boolean
        parseChannel(ChannelValues cv, Node n)
                throws FeederException {
            boolean ret = true;

            if (n.getNodeName().equalsIgnoreCase("itunes:summary"))
                setValue(cv.description, getTextValue(n));
            else if (n.getNodeName().equalsIgnoreCase("itunes:image")) {
                NamedNodeMap nnm = n.getAttributes();
                Node img = nnm.getNamedItem("href");
                if (null != img)
                    setValue(cv.imageref, img.getNodeValue());
            } else
                ret = false;

            return ret;
        }

        @Override
        boolean
        parseItem(ItemValues iv, Node n)
                throws FeederException {
            boolean ret = true;

            if (n.getNodeName().equalsIgnoreCase("itunes:summary"))
                setValue(iv.description, getTextValue(n));
            else if (n.getNodeName().equalsIgnoreCase("itunes:duration"))
                setValue(iv.enclosure_length, getTextValue(n));
            else
                ret = false;

            return ret;
        }

    }

    // ========================================
    //        To Support 'dublincore(dc)' Namespace
    // ========================================
    private class NSDcParser extends NSParser {
        NSDcParser() {
            super(PRI_DC);
        }

        @Override
        boolean
        parseItem(ItemValues iv, Node n)
                throws FeederException {
            boolean ret = true;

            if (n.getNodeName().equalsIgnoreCase("dc:date"))
                setValue(iv.pubDate, getTextValue(n));
            else
                ret = false;

            return ret;
        }

    }
    // ========================================
    //        Default RSS Parser
    // ========================================
    private class NSDefaultParser extends NSParser {
        NSDefaultParser() {
            super(PRI_DEFAULT);
        }

        private void
        nodeImage(ChannelValues cv, Node n)
                throws FeederException {
            n = n.getFirstChild();
            while (null != n) {
                if (n.getNodeName().equalsIgnoreCase("url")) {
                    setValue(cv.imageref, getTextValue(n));
                    return;
                }
                n = n.getNextSibling();
            }
        }

        private void
        nodeEnclosure(ItemValues iv, Node n) {
            NamedNodeMap nnm = n.getAttributes();
            n = nnm.getNamedItem("url");
            if (null != n)
                setValue(iv.enclosure_url, n.getNodeValue());

            n = nnm.getNamedItem("length");
            if (null != n)
                setValue(iv.enclosure_length, n.getNodeValue());

            n = nnm.getNamedItem("type");
            if (null != n)
                setValue(iv.enclosure_type, n.getNodeValue());
        }

        @Override
        boolean
        parseChannel(ChannelValues cv, Node n)
                throws FeederException {
            boolean ret = true;

            if (n.getNodeName().equalsIgnoreCase("title"))
                setValue(cv.title, getTextValue(n));
            else if (n.getNodeName().equalsIgnoreCase("description"))
                setValue(cv.description, getTextValue(n));
            else if (n.getNodeName().equalsIgnoreCase("image"))
                nodeImage(cv, n);
            else
                ret = false;

            return ret;
        }

        @Override
        boolean
        parseItem(ItemValues iv, Node n)
                throws FeederException {
            boolean ret = true;

            if (n.getNodeName().equalsIgnoreCase("title"))
                setValue(iv.title, getTextValue(n));
            else if (n.getNodeName().equalsIgnoreCase("link"))
                setValue(iv.link, getTextValue(n));
            else if (n.getNodeName().equalsIgnoreCase("description"))
                setValue(iv.description, getTextValue(n));
            else if (n.getNodeName().equalsIgnoreCase("enclosure"))
                nodeEnclosure(iv, n);
            else if (n.getNodeName().equalsIgnoreCase("pubDate"))
                setValue(iv.pubDate, getTextValue(n));
            else
                ret = false;

            return ret;
        }
    }

    // ===========================================================
    //
    // ===========================================================

    private RSSAttr
    nodeRssAttr(Node n) {
        RSSAttr rss = new RSSAttr();
        NamedNodeMap nnm = n.getAttributes();
        Node nVer = nnm.getNamedItem("version");
        if (null != nVer)
            rss.ver = nVer.getNodeValue();

        // Some element from 'itunes' and 'dc' is supported.
        // So, check it!
        if (null != nnm.getNamedItem("xmlns:itunes"))
            rss.nsl.addLast("itunes");

        if (null != nnm.getNamedItem("xmlns:dc"))
            rss.nsl.addLast("dc");

        return rss;
    }

    private void
    nodeChannel(Result res, NSParser[] parser, Node chn)
            throws FeederException {
        ChannelValues cv = new ChannelValues();
        ItemValues iv = new ItemValues();
        // count number of items in this channel

        LinkedList<Feed.Item.ParD> iteml = new LinkedList<Feed.Item.ParD>();
        cv.init();
        Node n = chn.getFirstChild();
        while (null != n) {
            if (n.getNodeName().equalsIgnoreCase("item")) {
                // Parsing elements of 'item'
                iv.init(); // to reuse
                Node in = n.getFirstChild();
                while (null != in) {
                    for (NSParser p : parser) {
                        if (p.parseItem(iv, in))
                            break; // handled
                    }
                    in = in.getNextSibling();
                }
                Feed.Item.ParD item = new Feed.Item.ParD();
                iv.set(item);
                iteml.addLast(item);
            } else {
                for (NSParser p : parser) {
                    if (p.parseChannel(cv, n))
                        break; // handled
                }
            }
            n = n.getNextSibling();
        }

        cv.set(res.channel);
        res.items = iteml.toArray(new Feed.Item.ParD[0]);
    }

    // false (fail)
    private boolean
    verifyNotNullPolicy(Result res) {
        if (null == res.channel.title)
            return false;

        for (Feed.Item.ParD item : res.items)
            if (null == item.title)
                return false;

        return true;
    }

    @Override
    public String
    dump(UnexpectedExceptionHandler.DumpLevel lv) {
        return "[ RSSParser ]";
    }

    public Result
    parse(Document dom)
            throws FeederException {
        Result res = null;
        UnexpectedExceptionHandler.S().registerModule(this);
        try {
            Element root = dom.getDocumentElement();
            verifyFormat(root.getNodeName().equalsIgnoreCase("rss"));

            RSSAttr rssAttr = nodeRssAttr(root);
            /* Remove version check... parser can parse.. lower version too!
            if (!rssAttr.ver.equals("2.0"))
                throw new FeederException(Err.ParserUnsupportedVersion);
            */

            res = new Result();

            // Set parser
            // NOTE : Should we save name space list all...???
            LinkedList<NSParser> pl = new LinkedList<NSParser>();
            for (String s : rssAttr.nsl.toArray(new String[0])) {
                NSParser p = null;
                if (s.equals("itunes")) {
                    p = new NSItunesParser();
                    res.channel.type = Feed.Channel.ChannTypeMedia;
                } else if (s.equals("dc"))
                    p = new NSDcParser();
                else
                    eAssert(false); // Not-supported namespace is parsed!!
                pl.add(p);
            }
            pl.add(new NSDefaultParser());

            // For Channel node
            Node n = findNodeByNameFromSiblings(root.getFirstChild(), "channel");

            nodeChannel(res, pl.toArray(new NSParser[0]), n);

            if (!verifyNotNullPolicy(res))
                throw new FeederException(Err.ParserUnsupportedFormat);
            //logI(feed.channel.dump());
        } finally {
            UnexpectedExceptionHandler.S().unregisterModule(this);
        }
        return res;
    }
}
